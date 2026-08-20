import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The sole UI-to-core adapter. Swing works with UiFrame and editor commands;
 * only this class creates or reads the mutable simulation objects.
 */
public final class SimulationSession {
    private static final int MINIMUM_DIMENSION = 2;
    /** Scenario snapshots kept for undo/redo; older entries are discarded first. */
    private static final int UNDO_LIMIT = 100;
    /**
     * Frames replayed per lock acquisition. Small chunks bound how long a
     * background seek can hold the session lock, keeping EDT edits responsive.
     */
    private static final int REPLAY_CHUNK_FRAMES = 60;

    private int width;
    private int height;
    private UiTerrain[] terrain;
    private final List<UnitDraft> drafts = new ArrayList<>();
    private int selectedDraft;

    private GridMap map;
    private Stage stage;
    private List<FrameTrace> lastTraces = List.of();
    private List<Boolean> trajectoryBreaks = List.of();
    /** Immutable timeline state atomically published to EDT readers. */
    private volatile Timeline timeline = Timeline.EMPTY;
    /** The first frame at which every unit is terminal, or -1 while any unit lives. */
    private volatile int terminalFrame = -1;
    private volatile long scenarioRevision;
    /**
     * UI playback owns the external phase clock passed to the core. Rebuilding a
     * scenario starts a new playback, so its first simulated frame is always 0.
     */
    private long globalFrame;
    /** Recorded combat injections; each applies before the tick that produces its frame + 1. */
    private final List<RunEvent> runEvents = new ArrayList<>();
    /** Scenario states before each applied edit, oldest first. */
    private final ArrayDeque<ScenarioSnapshot> undoStack = new ArrayDeque<>();
    /** States undone and available for redo, oldest first. */
    private final ArrayDeque<ScenarioSnapshot> redoStack = new ArrayDeque<>();

    private List<UiTerrain> cachedTerrainView;
    private PathMap cachedSegmentSource;
    private List<UiPathSegment> cachedSegments = List.of();

    /** One route's editable data; the scenario holds one draft per unit. */
    private record UnitDraft(UiCell spawn, UiCell endpoint, List<UiCheckpoint> checkpoints,
                             UiMovementMode movementMode, float attributeSpeed,
                             boolean allowDiagonalMove) {
        UnitDraft withSpawn(UiCell value) {
            return new UnitDraft(value, endpoint, checkpoints, movementMode, attributeSpeed,
                    allowDiagonalMove);
        }

        UnitDraft withEndpoint(UiCell value) {
            return new UnitDraft(spawn, value, checkpoints, movementMode, attributeSpeed,
                    allowDiagonalMove);
        }

        UnitDraft withMovementMode(UiMovementMode value) {
            return new UnitDraft(spawn, endpoint, checkpoints, value, attributeSpeed,
                    allowDiagonalMove);
        }

        UnitDraft withAttributeSpeed(float value) {
            return new UnitDraft(spawn, endpoint, checkpoints, movementMode, value,
                    allowDiagonalMove);
        }

        UnitDraft withAllowDiagonalMove(boolean value) {
            return new UnitDraft(spawn, endpoint, checkpoints, movementMode, attributeSpeed, value);
        }
    }

    /** Deep copy of every editable scenario field, captured before each edit. */
    private record ScenarioSnapshot(int width, int height, UiTerrain[] terrain,
                                     List<UnitDraft> drafts, int selectedDraft) {
    }

    /** Signals a normal attempt to advance beyond a confirmed terminal frame. */
    public static final class TerminalStateException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        TerminalStateException(String message) {
            super(message);
        }
    }

    public SimulationSession() {
        loadDemoScenario();
    }

    public synchronized void newScenario(int newWidth, int newHeight) {
        requireDimensions(newWidth, newHeight);
        pushUndo();
        initializeScenario(newWidth, newHeight);
        rebuildSimulator();
    }

    public synchronized void loadDemoScenario() {
        pushUndo();
        initializeScenario(12, 8);
        UnitDraft demo = new UnitDraft(new UiCell(1, 1), new UiCell(10, 6),
                new ArrayList<>(List.of(UiCheckpoint.move(new UiCell(5, 1)),
                        UiCheckpoint.move(new UiCell(5, 5)))),
                UiMovementMode.GROUND, 1f, true);
        drafts.clear();
        drafts.add(demo);
        selectedDraft = 0;
        setTerrainDirect(new UiCell(7, 3), UiTerrain.BOX);
        setTerrainDirect(new UiCell(7, 4), UiTerrain.PIT);
        setTerrainDirect(new UiCell(7, 5), UiTerrain.WALL);
        rebuildSimulator();
    }

    // ----- unit management -------------------------------------------------

    public synchronized int unitCount() {
        return drafts.size();
    }

    public synchronized int selectedDraftIndex() {
        return selectedDraft;
    }

    /** Selects which route subsequent editor commands and combat injections target. */
    public synchronized void selectDraft(int index) {
        if (index < 0 || index >= drafts.size()) {
            throw new IllegalArgumentException("Unit index is outside the unit list");
        }
        selectedDraft = index;
    }

    /** Adds a clone of the selected route as a new unit and selects it. */
    public synchronized void addDraft() {
        pushUndo();
        drafts.add(cloneDraft(draft()));
        selectedDraft = drafts.size() - 1;
        rebuildSimulator();
    }

    public synchronized void removeDraft(int index) {
        if (drafts.size() <= 1) {
            throw new IllegalArgumentException("至少保留一个单位");
        }
        if (index < 0 || index >= drafts.size()) {
            throw new IllegalArgumentException("Unit index is outside the unit list");
        }
        pushUndo();
        drafts.remove(index);
        selectedDraft = Math.min(selectedDraft, drafts.size() - 1);
        rebuildSimulator();
    }

    // ----- terrain editing --------------------------------------------------

    /**
     * Applies an editor terrain change.
     *
     * @return false when the cell is occupied by a route point of any unit, in
     *         which case nothing is changed and the caller should tell the
     *         user why.
     */
    public synchronized boolean setTerrain(UiCell cell, UiTerrain value) {
        requireInside(cell);
        if (value != UiTerrain.OPEN && isRouteCell(cell)) {
            return false;
        }
        int index = indexOf(cell);
        if (terrain[index] == value) {
            return true;
        }
        pushUndo();
        terrain[index] = value;
        rebuildSimulator();
        return true;
    }

    public synchronized void placeSpawn(UiCell cell) {
        requireInside(cell);
        requireNotOnEndpointOrCheckpoint(cell, "起点");
        pushUndo();
        updateDraft(draft().withSpawn(cell));
        ensureOpen(cell);
        rebuildSimulator();
    }

    public synchronized void placeEndpoint(UiCell cell) {
        requireInside(cell);
        requireNotOnSpawnOrCheckpoint(cell, "终点");
        pushUndo();
        updateDraft(draft().withEndpoint(cell));
        ensureOpen(cell);
        rebuildSimulator();
    }

    private void requireNotOnEndpointOrCheckpoint(UiCell cell, String what) {
        if (cell.equals(draft().endpoint())) {
            throw new IllegalArgumentException(what + "不能与终点同格");
        }
        requireNotOnCheckpoint(cell, what);
    }

    private void requireNotOnSpawnOrCheckpoint(UiCell cell, String what) {
        if (cell.equals(draft().spawn())) {
            throw new IllegalArgumentException(what + "不能与起点同格");
        }
        requireNotOnCheckpoint(cell, what);
    }

    private void requireNotOnCheckpoint(UiCell cell, String what) {
        for (UiCheckpoint checkpoint : draft().checkpoints()) {
            if (cell.equals(checkpoint.cell())) {
                throw new IllegalArgumentException(
                        what + "不能与检查点同格 (" + cell.x() + ", " + cell.y() + ")");
            }
        }
    }

    // ----- checkpoint editing ------------------------------------------------

    /**
     * Appends a checkpoint after validating the resulting route. Route rules
     * (for example DISAPPEAR needing a later APPEAR_AT_POS) reject invalid
     * edits with an exception before anything is changed.
     */
    public synchronized void addCheckpoint(UiCheckpoint checkpoint) {
        List<UiCheckpoint> next = new ArrayList<>(draft().checkpoints());
        next.add(checkpoint);
        commitCheckpoints(next);
    }

    /** Inserts a checkpoint before the given index; pass size() to append. */
    public synchronized void insertCheckpointBefore(int index, UiCheckpoint checkpoint) {
        if (index < 0 || index > draft().checkpoints().size()) {
            throw new IllegalArgumentException("Insertion index is outside the checkpoint list");
        }
        List<UiCheckpoint> next = new ArrayList<>(draft().checkpoints());
        next.add(index, checkpoint);
        commitCheckpoints(next);
    }

    /** Replaces a checkpoint's type and parameters, keeping its map cell when the new type uses one. */
    public synchronized void updateCheckpoint(int index, UiCheckpointType type, float value, int area) {
        updateCheckpoint(index, type, null, value, area);
    }

    /** Like {@link #updateCheckpoint(int, UiCheckpointType, float, int)} but moves a point checkpoint to {@code cell}. */
    public synchronized void updateCheckpoint(int index, UiCheckpointType type, UiCell cell,
                                              float value, int area) {
        requireCheckpointIndex(index);
        List<UiCheckpoint> current = draft().checkpoints();
        UiCheckpoint previous = current.get(index);
        UiCheckpoint updated = new UiCheckpoint(type,
                type.hasPoint() ? (cell != null ? cell : previous.cell()) : null,
                type.usesSeconds() ? value : 0f, type.usesArea() ? area : 0);
        if (type.hasPoint() && updated.cell() == null) {
            throw new IllegalArgumentException("检查点 " + (index + 1)
                    + " 没有可保留的地图坐标，无法改为「" + type.label() + "」");
        }
        List<UiCheckpoint> next = new ArrayList<>(current);
        next.set(index, updated);
        commitCheckpoints(next);
    }

    /** Moves a checkpoint one slot up (-1) or down (+1); out-of-range moves are ignored. */
    public synchronized void moveCheckpoint(int index, int offset) {
        requireCheckpointIndex(index);
        List<UiCheckpoint> current = draft().checkpoints();
        int target = index + offset;
        if (target < 0 || target >= current.size()) {
            return;
        }
        List<UiCheckpoint> next = new ArrayList<>(current);
        UiCheckpoint moved = next.remove(index);
        next.add(target, moved);
        commitCheckpoints(next);
    }

    public synchronized void removeCheckpoint(int index) {
        if (index < 0 || index >= draft().checkpoints().size()) {
            return;
        }
        List<UiCheckpoint> next = new ArrayList<>(draft().checkpoints());
        next.remove(index);
        commitCheckpoints(next);
    }

    public synchronized void clearCheckpoints() {
        if (draft().checkpoints().isEmpty()) {
            return;
        }
        commitCheckpoints(new ArrayList<>());
    }

    private void requireCheckpointIndex(int index) {
        if (index < 0 || index >= draft().checkpoints().size()) {
            throw new IllegalArgumentException("Checkpoint index is outside the list");
        }
    }

    /**
     * Validates the candidate list against the selected route's settings, then
     * commits it. A probe Route performs the same validation the core
     * performs, so an invalid edit is rejected without touching the scenario.
     */
    private void commitCheckpoints(List<UiCheckpoint> next) {
        UnitDraft current = draft();
        for (int index = 0; index < next.size(); index++) {
            UiCell cell = next.get(index).cell();
            if (cell == null) {
                continue;
            }
            if (cell.equals(current.spawn()) || cell.equals(current.endpoint())) {
                throw new IllegalArgumentException(
                        "检查点不能与起点/终点同格 (" + cell.x() + ", " + cell.y() + ")");
            }
            for (int other = index + 1; other < next.size(); other++) {
                if (cell.equals(next.get(other).cell())) {
                    throw new IllegalArgumentException("Cell (" + cell.x() + ", " + cell.y()
                            + ") already has a checkpoint");
                }
            }
        }
        probeRoute(current.spawn(), current.endpoint(), next, current.movementMode(),
                current.allowDiagonalMove());
        pushUndo();
        current.checkpoints().clear();
        current.checkpoints().addAll(next);
        for (UiCheckpoint checkpoint : current.checkpoints()) {
            if (checkpoint.cell() != null) {
                ensureOpen(checkpoint.cell());
            }
        }
        rebuildSimulator();
    }

    // ----- route settings ----------------------------------------------------

    public synchronized void setMovementMode(UiMovementMode value) {
        UnitDraft current = draft();
        if (current.movementMode() == value) {
            return;
        }
        pushUndo();
        updateDraft(current.withMovementMode(value));
        rebuildSimulator();
    }

    public synchronized void setAttributeSpeed(float value) {
        if (!Float.isFinite(value) || value < 0.1f) {
            throw new IllegalArgumentException("Movement speed must be at least 0.1");
        }
        UnitDraft current = draft();
        if (current.attributeSpeed() == value) {
            return;
        }
        pushUndo();
        updateDraft(current.withAttributeSpeed(value));
        rebuildSimulator();
    }

    public synchronized void setAllowDiagonalMove(boolean value) {
        UnitDraft current = draft();
        if (current.allowDiagonalMove() == value) {
            return;
        }
        pushUndo();
        updateDraft(current.withAllowDiagonalMove(value));
        rebuildSimulator();
    }

    // ----- undo / redo -------------------------------------------------------

    /** Whether a scenario edit is available to undo. */
    public synchronized boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Whether an undone edit is available to redo. */
    public synchronized boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /**
     * Reverts the most recent scenario edit. Scenario edits are map and route
     * changes (including new/demo/import); combat injections and playback
     * progress are run state, not scenario state, and are not undoable. Like
     * every scenario edit, undoing restarts playback from S[0].
     *
     * @return false when there is nothing to undo
     */
    public synchronized boolean undo() {
        ScenarioSnapshot previous = undoStack.pollLast();
        if (previous == null) {
            return false;
        }
        redoStack.addLast(captureScenario());
        restoreScenario(previous);
        return true;
    }

    public synchronized boolean redo() {
        ScenarioSnapshot next = redoStack.pollLast();
        if (next == null) {
            return false;
        }
        undoStack.addLast(captureScenario());
        restoreScenario(next);
        return true;
    }

    /** Current map dimensions, for UI layout decisions that must not build snapshots. */
    public synchronized int mapWidth() {
        return width;
    }

    public synchronized int mapHeight() {
        return height;
    }

    /** Remembers the state before an edit; a fresh edit invalidates the redo branch. */
    private void pushUndo() {
        if (terrain == null) {
            // The very first scenario load has no prior state to restore.
            return;
        }
        undoStack.addLast(captureScenario());
        if (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeFirst();
        }
        redoStack.clear();
    }

    private ScenarioSnapshot captureScenario() {
        List<UnitDraft> copies = new ArrayList<>(drafts.size());
        for (UnitDraft draft : drafts) {
            copies.add(cloneDraft(draft));
        }
        return new ScenarioSnapshot(width, height, terrain.clone(), copies, selectedDraft);
    }

    private void restoreScenario(ScenarioSnapshot snapshot) {
        width = snapshot.width();
        height = snapshot.height();
        terrain = snapshot.terrain().clone();
        drafts.clear();
        for (UnitDraft draft : snapshot.drafts()) {
            drafts.add(cloneDraft(draft));
        }
        selectedDraft = snapshot.selectedDraft();
        rebuildSimulator();
    }

    private static UnitDraft cloneDraft(UnitDraft draft) {
        return new UnitDraft(draft.spawn(), draft.endpoint(),
                new ArrayList<>(draft.checkpoints()), draft.movementMode(),
                draft.attributeSpeed(), draft.allowDiagonalMove());
    }

    // ----- playback ----------------------------------------------------------

    public synchronized UiSnapshot resetSimulation() {
        rebuildSimulator();
        return snapshot();
    }

    public synchronized boolean canTick() {
        return terminalFrame < 0 || currentFrame() < terminalFrame;
    }

    /** Advances one frame and returns the selected unit's snapshot. */
    public synchronized UiSnapshot tick() {
        return tickFrame().units().get(selectedDraft);
    }

    /** Advances one frame and returns the stage-wide frame view. */
    public synchronized UiFrame tickFrame() {
        if (!canTick()) {
            throw new TerminalStateException("Simulation is terminal at frame " + terminalFrame
                    + "; future frames are not defined");
        }
        advanceOneFrame();
        UiFrame result = snapshotFrame();
        int frame = result.frame();
        Timeline current = timeline;
        if (frame == current.size()) {
            timeline = current.append(result);
        } else if (frame < current.size()) {
            timeline = current.replace(frame, result);
        } else {
            throw new IllegalStateException("Timeline gap before frame " + frame);
        }
        if (allUnitsTerminal()) {
            terminalFrame = frame;
        }
        return result;
    }

    /** Advance only core state and snapshot dependencies, without allocating a snapshot. */
    private void advanceOneFrame() {
        // Keep the externally supplied phase exactly aligned with S[n] -> S[n+1].
        if (globalFrame != currentFrame()) {
            throw new IllegalStateException("Simulation frame drift: expected global frame "
                    + currentFrame() + ", got " + globalFrame);
        }
        applyRunEvents(currentFrame(), globalFrame);
        lastTraces = stage.tick(globalFrame++);
        int frame = currentFrame();
        Timeline current = timeline;
        List<UiSnapshot> previousUnits = frame > 0 && frame - 1 < current.size()
                ? current.get(frame - 1).units()
                : List.of();
        List<Boolean> breaks = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            FrameTrace trace = lastTraces.get(index);
            UiSnapshot previous = index < previousUnits.size() ? previousUnits.get(index) : null;
            breaks.add(isTrajectoryBreak(trace, previous));
        }
        trajectoryBreaks = breaks;
    }

    /** Snapshot of the selected unit for single-unit callers and legacy checks. */
    public synchronized UiSnapshot snapshot() {
        return buildUnitSnapshot(stage.simulator(selectedDraft), draft(), selectedDraft);
    }

    /** Stage-wide frame view containing every unit. */
    public synchronized UiFrame snapshotFrame() {
        List<UiSnapshot> units = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            units.add(buildUnitSnapshot(stage.simulator(index), drafts.get(index), index));
        }
        return new UiFrame(currentFrame(), stage.simulator(0).clock().playTime(), units);
    }

    private UiSnapshot buildUnitSnapshot(PathfindingSimulator simulator, UnitDraft unitDraft,
                                         int unitIndex) {
        UnitState unit = simulator.unit();
        PathMap activePathMap = activePathMap(simulator, unitDraft);
        TileCoord cursorTile = TileCoord.fromPosition(unit.cursorPosition());
        FrameTrace trace = lastTraceFor(unitIndex);

        return new UiSnapshot(
                width,
                height,
                terrainView(),
                unitDraft.spawn().center(),
                unitDraft.endpoint().center(),
                List.copyOf(unitDraft.checkpoints()),
                unitDraft.movementMode(),
                unitDraft.attributeSpeed(),
                unitDraft.allowDiagonalMove(),
                simulator.frame(),
                modeLabel(unit.mode()),
                unit.routeProgress().checkpointIndex(),
                unit.routeProgress().completed(),
                isTerminalMode(unit.mode()),
                unit.bound(),
                toUiPoint(unit.entityPosition()),
                toUiPoint(unit.cursorPosition()),
                toUiPoint(unit.inertiaVelocity()),
                toUiPoint(unit.cachedAvoidance()),
                trace == null ? UiPoint.ZERO : toUiPoint(trace.givenDirection()),
                map.contains(cursorTile) ? toUiCell(cursorTile) : null,
                trace == null || trace.nextNode() == null ? null : toUiCell(trace.nextNode()),
                currentTarget(simulator, unitDraft, activePathMap, unitIndex),
                trace != null && trace.avoidanceRecomputed(),
                trace == null ? "" : trace.transition(),
                simulator.clock().playTime(),
                pathSegments(activePathMap, coreMovementMode(unitDraft.movementMode())),
                trajectoryBreakFor(unitIndex));
    }

    /**
     * Return the exact frame S[n], where S[0] is the un-ticked birth state and
     * S[n] is obtained by ticking global frames 0 through n - 1. A backwards
     * seek rebuilds the mutable simulators and replays from S[0], never using
     * the simulator's finite trace ring as the timeline source.
     */
    public UiFrame seekFrame(long targetFrame) {
        if (targetFrame < 0L || targetFrame > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("Frame must be between 0 and " + (Integer.MAX_VALUE - 1L));
        }
        int target = (int) targetFrame;
        long expectedRevision;
        synchronized (this) {
            ensureTimelineInitialized();
            rejectBeyondTerminal(target);
            expectedRevision = scenarioRevision;
            if (target < currentFrame()) {
                rebuildSimulator(false);
            }
        }

        while (true) {
            checkReplayInterrupted();
            synchronized (this) {
                if (scenarioRevision != expectedRevision) {
                    throw new IllegalStateException("Replay request was superseded by a scenario edit");
                }
                if (currentFrame() >= target) {
                    return timeline.get(target);
                }
                int chunkEnd = Math.min(target, currentFrame() + REPLAY_CHUNK_FRAMES);
                while (currentFrame() < chunkEnd) {
                    checkReplayInterrupted();
                    if (currentFrame() + 1 < timeline.size()) {
                        advanceOneFrame();
                    } else {
                        rejectBeyondTerminal(target);
                        tickFrame();
                    }
                }
            }
            Thread.yield();
        }
    }

    /** Alias used by UI code that treats the timeline as the playback source. */
    public UiFrame stateAtFrame(long targetFrame) {
        return seekFrame(targetFrame);
    }

    /**
     * Return a generated frame without replaying or changing the mutable
     * simulators. This is intentionally nullable so a slider can show only
     * confirmed frames while a background seek is pending.
     */
    public UiFrame generatedStateAtFrame(long targetFrame) {
        if (targetFrame < 0L || targetFrame > Integer.MAX_VALUE - 1L) {
            return null;
        }
        int frame = (int) targetFrame;
        Timeline published = timeline;
        return frame < published.size() ? published.get(frame) : null;
    }

    /** Whether the requested frame is already present in the deterministic cache. */
    public boolean hasGeneratedFrame(long targetFrame) {
        return generatedStateAtFrame(targetFrame) != null;
    }

    /** True once every unit is BLOCKED or COMPLETED and the frame is confirmed. */
    public boolean isTerminal() {
        return terminalFrame >= 0;
    }

    /** Terminal frame, or -1 while any unit still lives. */
    public int terminalFrame() {
        return terminalFrame;
    }

    /** Number of contiguous frames currently generated, including S[0]. */
    public int generatedFrameCount() {
        return timeline.size();
    }

    /** Highest generated frame index, or 0 for a fresh scenario. */
    public int generatedLastFrame() {
        return timeline.size() - 1;
    }

    /** Immutable copy of all generated frames for actual-trajectory rendering. */
    public List<UiFrame> generatedStates() {
        return timeline.asList();
    }

    /** Revision increments whenever an edit/reset rebuilds the scenario. */
    public long scenarioRevision() {
        return scenarioRevision;
    }

    /** Parse an exact UI time/frame field without floating point conversion. */
    public static long parseTimeToFrame(String text) {
        return SimulationTime.parseFrame(text);
    }

    // ----- combat injections ---------------------------------------------------

    /**
     * Records a stun on the selected unit starting with the next tick.
     * Recording alone never mutates the simulator, so replays stay
     * deterministic: the event is re-applied at the same frame whenever the
     * timeline is replayed.
     */
    public synchronized void applyStun(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0f) {
            throw new IllegalArgumentException("眩晕时长必须是非负有限数");
        }
        recordRunEvent(EventKind.STUN, Vec2f.ZERO, seconds);
    }

    /** Records a constant-velocity push on the selected unit starting with the next tick. */
    public synchronized void applyDisplacement(float velocityX, float velocityY, float seconds) {
        if (!Float.isFinite(velocityX) || !Float.isFinite(velocityY)
                || !Float.isFinite(seconds) || seconds < 0f) {
            throw new IllegalArgumentException("击退参数必须是有限数，时长非负");
        }
        recordRunEvent(EventKind.DISPLACE, new Vec2f(velocityX, velocityY), seconds);
    }

    /** Records a bind or unbind on the selected unit starting with the next tick. */
    public synchronized void setUnitBound(boolean bound) {
        recordRunEvent(bound ? EventKind.BIND : EventKind.UNBIND, Vec2f.ZERO, 0f);
    }

    /**
     * The bound state the UI should display for the selected unit: the actual
     * state folded with bind events already scheduled at the current frame, so
     * a scheduled-but-not-yet-applied bind reads as on instead of snapping back.
     */
    public synchronized boolean bindStateForDisplay() {
        boolean bound = stage.simulator(selectedDraft).unit().bound();
        for (RunEvent event : runEvents) {
            if (event.unit() == selectedDraft && event.frame() == currentFrame()) {
                if (event.kind() == EventKind.BIND) {
                    bound = true;
                } else if (event.kind() == EventKind.UNBIND) {
                    bound = false;
                }
            }
        }
        return bound;
    }

    private void recordRunEvent(EventKind kind, Vec2f velocity, float seconds) {
        int frame = currentFrame();
        if (terminalFrame >= 0 && frame >= terminalFrame) {
            throw new IllegalStateException("模拟已到达终态，无法注入状态");
        }
        PathfindingSimulator target = stage.simulator(selectedDraft);
        UnitMode mode = target.unit().mode();
        if (mode == UnitMode.BLOCKED || mode == UnitMode.COMPLETED || mode == UnitMode.VANISHED) {
            throw new IllegalStateException("当前状态" + modeLabel(mode) + "下无法注入");
        }
        runEvents.add(new RunEvent(frame, selectedDraft, kind, velocity, seconds));
        Timeline current = timeline;
        if (current.size() > frame + 1) {
            // Frames past the injection point were generated by a run without
            // this event. Drop the invalidated tail so seeks, scrubs, and CSV
            // export can only ever observe states re-derived with the event.
            timeline = current.truncate(frame + 1);
            terminalFrame = -1;
        }
    }

    private void applyRunEvents(int frame, long globalFrame) {
        for (RunEvent event : runEvents) {
            if (event.frame() != frame) {
                continue;
            }
            PathfindingSimulator target = stage.simulator(event.unit());
            switch (event.kind()) {
                case STUN -> target.stun(globalFrame, event.seconds());
                case DISPLACE -> target.displace(globalFrame, event.velocity(), event.seconds());
                case BIND -> target.setBound(true);
                case UNBIND -> target.setBound(false);
            }
        }
    }

    private enum EventKind {
        STUN, DISPLACE, BIND, UNBIND
    }

    private record RunEvent(int frame, int unit, EventKind kind, Vec2f velocity, float seconds) {
    }

    // ----- export / import ------------------------------------------------------

    /** Canonical, non-misleading display for a frame time. */
    public static String formatFrameTime(long frame) {
        return SimulationTime.formatFrame(frame);
    }

    /** Serializes the current scenario (map, all routes, movement settings) as importable text. */
    public synchronized String exportScenario() {
        List<ScenarioCodec.UnitSpec> units = new ArrayList<>(drafts.size());
        for (UnitDraft draft : drafts) {
            units.add(new ScenarioCodec.UnitSpec(draft.spawn(), draft.endpoint(),
                    List.copyOf(draft.checkpoints()), draft.movementMode(),
                    draft.attributeSpeed(), draft.allowDiagonalMove()));
        }
        return ScenarioCodec.format(width, height, terrainView(), units);
    }

    /**
     * Replaces the current scenario with text produced by exportScenario().
     * All validation happens before any state changes, so a rejected import
     * leaves the current scenario untouched.
     */
    public synchronized void importScenario(String text) {
        ScenarioCodec.Scenario parsed = ScenarioCodec.parse(text);
        for (ScenarioCodec.TerrainEntry entry : parsed.terrain()) {
            if (entry.terrain() != UiTerrain.OPEN && conflictsWithRoute(entry.cell(), parsed)) {
                throw new IllegalArgumentException("Imported terrain at ("
                        + entry.cell().x() + ", " + entry.cell().y()
                        + ") conflicts with a route point");
            }
        }
        for (ScenarioCodec.UnitSpec unit : parsed.units()) {
            probeRoute(unit.spawn(), unit.endpoint(), unit.checkpoints(), unit.movementMode(),
                    unit.allowDiagonalMove());
        }
        pushUndo();
        initializeScenario(parsed.width(), parsed.height());
        drafts.clear();
        for (ScenarioCodec.UnitSpec unit : parsed.units()) {
            drafts.add(new UnitDraft(unit.spawn(), unit.endpoint(),
                    new ArrayList<>(unit.checkpoints()), unit.movementMode(),
                    unit.speed(), unit.allowDiagonalMove()));
        }
        selectedDraft = 0;
        for (ScenarioCodec.TerrainEntry entry : parsed.terrain()) {
            setTerrainDirect(entry.cell(), entry.terrain());
        }
        rebuildSimulator();
    }

    /** One CSV row per unit and generated frame, including S[0]. */
    public synchronized String exportTraceCsv() {
        StringBuilder csv = new StringBuilder();
        csv.append("unit,frame,mode,checkpoint,completed,entity_x,entity_y,cursor_x,cursor_y,")
                .append("velocity_x,velocity_y,avoidance_x,avoidance_y,transition\n");
        for (UiFrame frame : timeline.asList()) {
            for (int unit = 0; unit < frame.units().size(); unit++) {
                appendSnapshotRow(csv, unit, frame.units().get(unit));
            }
        }
        return csv.toString();
    }

    private static void appendSnapshotRow(StringBuilder csv, int unit, UiSnapshot snapshot) {
        csv.append(unit).append(',')
                .append(snapshot.frame()).append(',')
                .append(csvField(snapshot.unitMode())).append(',')
                .append(snapshot.activeCheckpoint()).append(',')
                .append(snapshot.completed()).append(',')
                .append(Float.toString(snapshot.entityPosition().x())).append(',')
                .append(Float.toString(snapshot.entityPosition().y())).append(',')
                .append(Float.toString(snapshot.cursorPosition().x())).append(',')
                .append(Float.toString(snapshot.cursorPosition().y())).append(',')
                .append(Float.toString(snapshot.inertiaVelocity().x())).append(',')
                .append(Float.toString(snapshot.inertiaVelocity().y())).append(',')
                .append(Float.toString(snapshot.avoidance().x())).append(',')
                .append(Float.toString(snapshot.avoidance().y())).append(',')
                .append(csvField(snapshot.transition())).append('\n');
    }

    private static String csvField(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static boolean conflictsWithRoute(UiCell cell, ScenarioCodec.Scenario parsed) {
        for (ScenarioCodec.UnitSpec unit : parsed.units()) {
            if (cell.equals(unit.spawn()) || cell.equals(unit.endpoint())) {
                return true;
            }
            for (UiCheckpoint checkpoint : unit.checkpoints()) {
                if (cell.equals(checkpoint.cell())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds a throwaway Route purely for validation, so an invalid edit or
     * import is rejected by the same rules the core enforces, before any
     * scenario state changes.
     */
    private static void probeRoute(UiCell spawn, UiCell endpoint, List<UiCheckpoint> checkpoints,
                                   UiMovementMode movementMode, boolean allowDiagonalMove) {
        new Route(toCorePoint(spawn.center()), toCorePoint(endpoint.center()),
                toCoreCheckpoints(new ArrayList<>(checkpoints)), coreMovementMode(movementMode),
                allowDiagonalMove, true, false);
    }

    private static List<Checkpoint> toCoreCheckpoints(List<UiCheckpoint> checkpoints) {
        List<Checkpoint> result = new ArrayList<>(checkpoints.size());
        for (UiCheckpoint checkpoint : checkpoints) {
            result.add(toCoreCheckpoint(checkpoint));
        }
        return result;
    }

    private static Checkpoint toCoreCheckpoint(UiCheckpoint checkpoint) {
        Vec2f point = checkpoint.cell() == null ? null : toCorePoint(checkpoint.cell().center());
        return switch (checkpoint.type().core()) {
            case MOVE -> Checkpoint.move(point);
            case PATROL_MOVE -> Checkpoint.patrolMove(point);
            case APPEAR_AT_POS -> Checkpoint.appearAt(point);
            case WAIT_FOR_SECONDS -> Checkpoint.waitForSeconds(checkpoint.value());
            case WAIT_FOR_PLAY_TIME -> Checkpoint.waitForPlayTime(checkpoint.value());
            case WAIT_CURRENT_FRAGMENT_TIME -> Checkpoint.waitForFragmentTime(checkpoint.value());
            case WAIT_CURRENT_WAVE_TIME -> Checkpoint.waitForWaveTime(checkpoint.value());
            case WAIT_BOSSRUSH_WAVE -> Checkpoint.waitForBossRushArea(checkpoint.area());
            case DISAPPEAR -> Checkpoint.disappear();
            case ALERT -> Checkpoint.alert();
        };
    }

    // ----- internal scenario state ----------------------------------------------

    private UnitDraft draft() {
        return drafts.get(selectedDraft);
    }

    private void updateDraft(UnitDraft updated) {
        drafts.set(selectedDraft, updated);
    }

    private int currentFrame() {
        return stage.simulator(0).frame();
    }

    private FrameTrace lastTraceFor(int unitIndex) {
        return unitIndex < lastTraces.size() ? lastTraces.get(unitIndex) : null;
    }

    private boolean trajectoryBreakFor(int unitIndex) {
        return unitIndex < trajectoryBreaks.size() && trajectoryBreaks.get(unitIndex);
    }

    private boolean allUnitsTerminal() {
        for (int index = 0; index < drafts.size(); index++) {
            if (!isTerminalMode(stage.simulator(index).unit().mode())) {
                return false;
            }
        }
        return true;
    }

    private void initializeScenario(int newWidth, int newHeight) {
        requireDimensions(newWidth, newHeight);
        width = newWidth;
        height = newHeight;
        terrain = new UiTerrain[width * height];
        for (int index = 0; index < terrain.length; index++) {
            terrain[index] = UiTerrain.OPEN;
        }
        int centerY = height / 2;
        drafts.clear();
        drafts.add(new UnitDraft(new UiCell(0, centerY), new UiCell(width - 1, centerY),
                new ArrayList<>(), UiMovementMode.GROUND, 1f, true));
        selectedDraft = 0;
        lastTraces = List.of();
        trajectoryBreaks = List.of();
        terminalFrame = -1;
        invalidateScenarioCaches();
    }

    private void rebuildSimulator() {
        rebuildSimulator(true);
    }

    private void rebuildSimulator(boolean resetTimeline) {
        map = new GridMap(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                UiCell cell = new UiCell(x, y);
                map.setRule(new TileCoord(x, y), coreRule(terrain[indexOf(cell)]));
            }
        }
        List<Stage.StageUnit> units = new ArrayList<>(drafts.size());
        for (UnitDraft draft : drafts) {
            units.add(new Stage.StageUnit(
                    new Route(toCorePoint(draft.spawn().center()), toCorePoint(draft.endpoint().center()),
                            toCoreCheckpoints(draft.checkpoints()),
                            coreMovementMode(draft.movementMode()), draft.allowDiagonalMove(),
                            true, false),
                    coreMovementMode(draft.movementMode()) == MovementMode.GROUND
                            ? UnitConfig.normalGround(draft.attributeSpeed())
                            : UnitConfig.normalFlying(draft.attributeSpeed())));
        }
        stage = new Stage(map, units);
        lastTraces = List.of();
        globalFrame = 0L;
        if (resetTimeline) {
            terminalFrame = -1;
        }
        trajectoryBreaks = List.of();
        invalidateScenarioCaches();
        if (allUnitsTerminal()) {
            terminalFrame = 0;
        }
        if (resetTimeline) {
            scenarioRevision++;
            timeline = Timeline.EMPTY.append(snapshotFrame());
            runEvents.clear();
        }
    }

    private void ensureTimelineInitialized() {
        if (timeline.size() == 0) {
            timeline = Timeline.EMPTY.append(snapshotFrame());
            if (allUnitsTerminal()) {
                terminalFrame = 0;
            }
        }
    }

    private void requireDimensions(int newWidth, int newHeight) {
        if (newWidth < MINIMUM_DIMENSION || newHeight < MINIMUM_DIMENSION) {
            throw new IllegalArgumentException("Map dimensions must be at least " + MINIMUM_DIMENSION);
        }
    }

    private void invalidateScenarioCaches() {
        cachedTerrainView = null;
        cachedSegmentSource = null;
        cachedSegments = List.of();
    }

    private PathMap activePathMap(PathfindingSimulator simulator, UnitDraft unitDraft) {
        RouteProgress progress = simulator.unit().routeProgress();
        int checkpointIndex = progress.checkpointIndex();
        return !progress.completed() && checkpointIndex < unitDraft.checkpoints().size()
                && simulator.checkpointOwnsPathMap(checkpointIndex)
                ? simulator.pathMapForCheckpoint(checkpointIndex)
                : simulator.endpointPathMap();
    }

    private UiPoint currentTarget(PathfindingSimulator simulator, UnitDraft unitDraft,
                                  PathMap activePathMap, int unitIndex) {
        if (simulator.unit().routeProgress().completed()) {
            return unitDraft.endpoint().center();
        }
        FrameTrace trace = lastTraceFor(unitIndex);
        if (trace != null && trace.target() != null) {
            return toUiPoint(trace.target());
        }
        return toUiPoint(activePathMap.target().center());
    }

    private List<UiPathSegment> pathSegments(PathMap activePathMap, MovementMode movement) {
        if (cachedSegmentSource == activePathMap) {
            return cachedSegments;
        }
        List<UiPathSegment> segments = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                TileCoord cell = new TileCoord(x, y);
                if (!map.passable(cell, movement) || !activePathMap.reachable(cell)) {
                    continue;
                }
                TileCoord nextNode = activePathMap.nextNode(cell);
                if (nextNode != null && !nextNode.equals(cell)) {
                    segments.add(new UiPathSegment(toUiCell(cell), toUiCell(nextNode)));
                }
            }
        }
        cachedSegments = List.copyOf(segments);
        cachedSegmentSource = activePathMap;
        return cachedSegments;
    }

    private List<UiTerrain> terrainView() {
        if (cachedTerrainView == null) {
            cachedTerrainView = List.of(terrain);
        }
        return cachedTerrainView;
    }

    private boolean isRouteCell(UiCell cell) {
        for (UnitDraft draft : drafts) {
            if (cell.equals(draft.spawn()) || cell.equals(draft.endpoint())) {
                return true;
            }
            for (UiCheckpoint checkpoint : draft.checkpoints()) {
                if (cell.equals(checkpoint.cell())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureOpen(UiCell cell) {
        terrain[indexOf(cell)] = UiTerrain.OPEN;
    }

    private void setTerrainDirect(UiCell cell, UiTerrain value) {
        terrain[indexOf(cell)] = value;
    }

    private void requireInside(UiCell cell) {
        if (cell.x() < 0 || cell.x() >= width || cell.y() < 0 || cell.y() >= height) {
            throw new IllegalArgumentException("Outside editor map: " + cell);
        }
    }

    private int indexOf(UiCell cell) {
        return cell.y() * width + cell.x();
    }

    private static MovementMode coreMovementMode(UiMovementMode movementMode) {
        return movementMode == UiMovementMode.GROUND ? MovementMode.GROUND : MovementMode.FLYING;
    }

    private static TileRule coreRule(UiTerrain terrain) {
        return switch (terrain) {
            case OPEN -> TileRule.open();
            case BOX -> TileRule.box();
            case PIT -> TileRule.pit();
            case WALL -> TileRule.impassable();
        };
    }

    private static UiPoint toUiPoint(Vec2f point) {
        return new UiPoint(point.x(), point.y());
    }

    private static Vec2f toCorePoint(UiPoint point) {
        return new Vec2f(point.x(), point.y());
    }

    private static UiCell toUiCell(TileCoord cell) {
        return new UiCell(cell.x(), cell.y());
    }

    private static String modeLabel(UnitMode mode) {
        return switch (mode) {
            case MOVE -> "移动";
            case BLOCKED -> "阻挡";
            case STUNNED -> "眩晕";
            case DISPLACED -> "位移";
            case VANISHED -> "消失";
            case COMPLETED -> "完成";
        };
    }

    private void rejectBeyondTerminal(int targetFrame) {
        if (terminalFrame >= 0 && targetFrame > terminalFrame) {
            throw new IllegalArgumentException("Requested frame " + targetFrame
                    + " is after terminal frame " + terminalFrame
                    + "; future terminal state is not defined");
        }
    }

    private static void checkReplayInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Replay request was cancelled");
        }
    }

    private static boolean isTerminalMode(UnitMode mode) {
        return mode == UnitMode.BLOCKED || mode == UnitMode.COMPLETED;
    }

    private static boolean isTrajectoryBreak(FrameTrace trace, UiSnapshot previous) {
        if (trace == null) {
            return true;
        }
        String transition = trace.transition();
        if (transition.contains("DISAPPEAR") || transition.contains("APPEAR_AT_POS")
                || trace.modeBefore() == UnitMode.VANISHED
                || trace.modeAfter() == UnitMode.VANISHED) {
            return true;
        }
        if (previous == null || trace.entityBefore() == null || trace.entityAfter() == null) {
            return true;
        }
        // A normal frame's entity position is exactly entityBefore + applied
        // displacement (all operations are float32). Portal relocation or any
        // other non-contiguous jump violates that identity and must break the
        // rendered path instead of drawing a false cross-map segment.
        Vec2f expected = new Vec2f(trace.entityBefore().x(), trace.entityBefore().y())
                .add(trace.appliedDisplacement());
        return !sameFloatBits(expected.x(), trace.entityAfter().x())
                || !sameFloatBits(expected.y(), trace.entityAfter().y())
                || !sameFloatBits(previous.entityPosition().x(), trace.entityBefore().x())
                || !sameFloatBits(previous.entityPosition().y(), trace.entityBefore().y());
    }

    private static boolean sameFloatBits(float left, float right) {
        return Float.floatToIntBits(left) == Float.floatToIntBits(right);
    }

    /**
     * Immutable (backing array, visible size) pair published as one reference.
     *
     * <p>The timeline deliberately retains every generated frame: exact seeks,
     * backward replays, and full-trajectory rendering all read arbitrary S[n].
     * Per-frame memory stays small because snapshots share the cached terrain
     * view and path-segment lists, but very long playback sessions therefore
     * grow without bound by design. The one exception is a combat injection
     * recorded below the generated frontier, which invalidates and drops the
     * divergent tail (see recordRunEvent).
     */
    private static final class Timeline {
        private static final Timeline EMPTY = new Timeline(new UiFrame[0], 0);
        private static final int MINIMUM_CAPACITY = 64;

        private final UiFrame[] items;
        private final int size;

        private Timeline(UiFrame[] items, int size) {
            this.items = items;
            this.size = size;
        }

        private int size() {
            return size;
        }

        private UiFrame get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Frame " + index + " is not generated");
            }
            return items[index];
        }

        private Timeline append(UiFrame value) {
            UiFrame[] target = items;
            if (size == target.length) {
                target = Arrays.copyOf(items, Math.max(MINIMUM_CAPACITY, size * 2));
            }
            target[size] = value;
            return new Timeline(target, size + 1);
        }

        private Timeline replace(int index, UiFrame value) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Timeline gap before frame " + index);
            }
            if (items[index] == value) {
                return this;
            }
            UiFrame[] copy = items.clone();
            copy[index] = value;
            return new Timeline(copy, size);
        }

        /**
         * Drops frames at and past newSize. The backing array is copied so a
         * later append cannot mutate a frame still visible through a stale
         * published reference.
         */
        private Timeline truncate(int newSize) {
            if (newSize <= 0 || newSize >= size) {
                return this;
            }
            return new Timeline(Arrays.copyOf(items, Math.max(MINIMUM_CAPACITY, newSize)), newSize);
        }

        private List<UiFrame> asList() {
            UiFrame[] source = items;
            int count = size;
            return new AbstractList<>() {
                @Override
                public UiFrame get(int index) {
                    if (index < 0 || index >= count) {
                        throw new IndexOutOfBoundsException("Frame " + index + " is not generated");
                    }
                    return source[index];
                }

                @Override
                public int size() {
                    return count;
                }
            };
        }
    }
}
