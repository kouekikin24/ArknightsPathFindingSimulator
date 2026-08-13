import java.util.ArrayList;
import java.util.List;

/**
 * The sole UI-to-core adapter. Swing works with UiSnapshot and editor commands;
 * only this class creates or reads the mutable simulation objects.
 */
public final class SimulationSession {
    private static final int MINIMUM_DIMENSION = 2;

    private int width;
    private int height;
    private UiTerrain[] terrain;
    private UiCell spawn;
    private UiCell endpoint;
    private final List<UiCell> checkpoints = new ArrayList<>();
    private UiMovementMode movementMode = UiMovementMode.GROUND;
    private float attributeSpeed = 1f;
    private boolean allowDiagonalMove = true;

    private GridMap map;
    private Route route;
    private PathfindingSimulator simulator;
    private FrameTrace lastTrace;
    /** Deterministic UI playback states. Index n is exactly S[n]. */
    private final List<UiSnapshot> timeline = new ArrayList<>();
    /** Atomically published immutable view for EDT/read-only playback queries. */
    private volatile List<UiSnapshot> publishedTimeline = List.of();
    /** The first confirmed terminal state, or -1 while the route is live. */
    private volatile int terminalFrame = -1;
    private boolean trajectoryBreak;
    private volatile long scenarioRevision;
    /** Invalidates in-flight local replays whenever the live playback changes. */
    private long playbackRevision;
    /**
     * UI playback owns the external phase clock passed to the core. Rebuilding a
     * scenario starts a new playback, so its first simulated frame is always 0.
     */
    private long globalFrame;

    public SimulationSession() {
        loadDemoScenario();
    }

    public synchronized void newScenario(int newWidth, int newHeight) {
        initializeScenario(newWidth, newHeight);
        rebuildSimulator();
    }

    public synchronized void loadDemoScenario() {
        initializeScenario(12, 8);
        spawn = new UiCell(1, 1);
        endpoint = new UiCell(10, 6);
        checkpoints.add(new UiCell(5, 1));
        checkpoints.add(new UiCell(5, 5));
        setTerrainDirect(new UiCell(7, 3), UiTerrain.BOX);
        setTerrainDirect(new UiCell(7, 4), UiTerrain.PIT);
        setTerrainDirect(new UiCell(7, 5), UiTerrain.WALL);
        rebuildSimulator();
    }

    public synchronized void setTerrain(UiCell cell, UiTerrain value) {
        requireInside(cell);
        if (value != UiTerrain.OPEN && isRouteCell(cell)) {
            return;
        }
        int index = indexOf(cell);
        if (terrain[index] == value) {
            return;
        }
        terrain[index] = value;
        rebuildSimulator();
    }

    public synchronized void placeSpawn(UiCell cell) {
        requireInside(cell);
        spawn = cell;
        ensureOpen(cell);
        rebuildSimulator();
    }

    public synchronized void placeEndpoint(UiCell cell) {
        requireInside(cell);
        endpoint = cell;
        ensureOpen(cell);
        rebuildSimulator();
    }

    public synchronized void addCheckpoint(UiCell cell) {
        requireInside(cell);
        if (checkpoints.contains(cell)) {
            return;
        }
        checkpoints.add(cell);
        ensureOpen(cell);
        rebuildSimulator();
    }

    public synchronized void removeCheckpoint(int index) {
        if (index < 0 || index >= checkpoints.size()) {
            return;
        }
        checkpoints.remove(index);
        rebuildSimulator();
    }

    public synchronized void clearCheckpoints() {
        if (checkpoints.isEmpty()) {
            return;
        }
        checkpoints.clear();
        rebuildSimulator();
    }

    public synchronized void setMovementMode(UiMovementMode value) {
        if (movementMode == value) {
            return;
        }
        movementMode = value;
        rebuildSimulator();
    }

    public synchronized void setAttributeSpeed(float value) {
        if (!Float.isFinite(value) || value < 0.1f) {
            throw new IllegalArgumentException("Movement speed must be at least 0.1");
        }
        if (attributeSpeed == value) {
            return;
        }
        attributeSpeed = value;
        rebuildSimulator();
    }

    public synchronized void setAllowDiagonalMove(boolean value) {
        if (allowDiagonalMove == value) {
            return;
        }
        allowDiagonalMove = value;
        rebuildSimulator();
    }

    public synchronized UiSnapshot resetSimulation() {
        rebuildSimulator();
        return snapshot();
    }

    public synchronized UiSnapshot tick() {
        if (terminalFrame >= 0 && simulator.frame() >= terminalFrame) {
            throw new IllegalStateException("Simulation is terminal at frame " + terminalFrame
                    + "; future frames are not defined");
        }
        // Keep the externally supplied phase exactly aligned with S[n] -> S[n+1].
        if (globalFrame != simulator.frame()) {
            throw new IllegalStateException("Simulation frame drift: expected global frame "
                    + simulator.frame() + ", got " + globalFrame);
        }
        lastTrace = simulator.tick(globalFrame++);
        UiSnapshot result = snapshot();
        UiSnapshot previous = result.frame() > 0 && result.frame() - 1 < timeline.size()
                ? timeline.get(result.frame() - 1)
                : null;
        trajectoryBreak = isTrajectoryBreak(lastTrace, previous);
        // Rebuild the snapshot with the break marker computed from this exact pair
        // of consecutive states. No route geometry is involved in this decision.
        result = snapshot();
        // A seek back truncates/replaces the future before the next normal tick.
        while (timeline.size() > result.frame() + 1) {
            timeline.remove(timeline.size() - 1);
        }
        if (result.frame() == timeline.size()) {
            timeline.add(result);
        } else if (result.frame() < timeline.size()) {
            timeline.set(result.frame(), result);
        } else {
            throw new IllegalStateException("Timeline gap before frame " + result.frame());
        }
        publishTimeline();
        if (isTerminalMode(simulator.unit().mode())) {
            terminalFrame = result.frame();
        }
        return result;
    }

    public synchronized UiSnapshot snapshot() {
        UnitState unit = simulator.unit();
        PathMap activePathMap = activePathMap();
        TileCoord cursorTile = TileCoord.fromPosition(unit.cursorPosition());

        return new UiSnapshot(
                width,
                height,
                terrainView(),
                spawn.center(),
                endpoint.center(),
                checkpointPoints(),
                movementMode,
                attributeSpeed,
                allowDiagonalMove,
                simulator.frame(),
                modeLabel(unit.mode()),
                unit.routeProgress().checkpointIndex(),
                unit.routeProgress().completed(),
                toUiPoint(unit.entityPosition()),
                toUiPoint(unit.cursorPosition()),
                toUiPoint(unit.inertiaVelocity()),
                toUiPoint(unit.cachedAvoidance()),
                lastTrace == null ? UiPoint.ZERO : toUiPoint(lastTrace.givenDirection()),
                map.contains(cursorTile) ? toUiCell(cursorTile) : null,
                lastTrace == null || lastTrace.nextNode() == null ? null : toUiCell(lastTrace.nextNode()),
                currentTarget(activePathMap),
                lastTrace != null && lastTrace.avoidanceRecomputed(),
                lastTrace == null ? "" : lastTrace.transition(),
                simulator.clock().playTime(),
                pathSegments(activePathMap),
                trajectoryBreak);
    }

    /**
     * Return the exact state S[n], where S[0] is the un-ticked birth state and
     * S[n] is obtained by ticking global frames 0 through n - 1. A backwards
     * seek rebuilds the mutable simulator and replays from S[0], never using the
     * simulator's finite trace ring as the timeline source.
     */
    public synchronized UiSnapshot seekFrame(long targetFrame) {
        if (targetFrame < 0L || targetFrame > Integer.MAX_VALUE - 1L) {
            throw new IllegalArgumentException("Frame must be between 0 and " + (Integer.MAX_VALUE - 1L));
        }
        ensureTimelineInitialized();
        int target = (int) targetFrame;
        rejectBeyondTerminal(target);
        int current = simulator.frame();
        if (target < current) {
            rebuildSimulator(false);
            while (simulator.frame() < target) {
                checkReplayInterrupted();
                rejectBeyondTerminal(target);
                tick();
            }
            while (timeline.size() > target + 1) {
                timeline.remove(timeline.size() - 1);
            }
            publishTimeline();
        } else {
            while (simulator.frame() < target) {
                checkReplayInterrupted();
                rejectBeyondTerminal(target);
                tick();
            }
        }
        return timeline.get(target);
    }

    /** Alias used by UI code that treats the timeline as the playback source. */
    public synchronized UiSnapshot stateAtFrame(long targetFrame) {
        return seekFrame(targetFrame);
    }

    /**
     * Return a generated state without replaying or changing the mutable
     * simulator. This is intentionally nullable so a slider can show only
     * confirmed frames while a background seek is pending.
     */
    public UiSnapshot generatedStateAtFrame(long targetFrame) {
        if (targetFrame < 0L || targetFrame > Integer.MAX_VALUE - 1L) {
            return null;
        }
        int frame = (int) targetFrame;
        List<UiSnapshot> published = publishedTimeline;
        return frame < published.size() ? published.get(frame) : null;
    }

    /** Whether the requested frame is already present in the deterministic cache. */
    public boolean hasGeneratedFrame(long targetFrame) {
        return generatedStateAtFrame(targetFrame) != null;
    }

    /** True once BLOCKED or COMPLETED has been reached and confirmed. */
    public boolean isTerminal() {
        return terminalFrame >= 0;
    }

    /** Terminal state frame, or -1 while the route is still live. */
    public int terminalFrame() {
        return terminalFrame;
    }

    /** Number of contiguous states currently generated, including S[0]. */
    public int generatedFrameCount() {
        return publishedTimeline.size();
    }

    /** Highest generated frame index, or 0 for a fresh scenario. */
    public int generatedLastFrame() {
        return publishedTimeline.size() - 1;
    }

    /** Immutable copy of all generated states for actual-trajectory rendering. */
    public List<UiSnapshot> generatedStates() {
        return publishedTimeline;
    }

    /** Revision increments whenever an edit/reset rebuilds the scenario. */
    public long scenarioRevision() {
        return scenarioRevision;
    }

    /** Parse an exact UI time/frame field without floating point conversion. */
    public static long parseTimeToFrame(String text) {
        return SimulationTime.parseFrame(text);
    }

    /** Canonical, non-misleading display for a frame time. */
    public static String formatFrameTime(long frame) {
        return SimulationTime.formatFrame(frame);
    }

    private void initializeScenario(int newWidth, int newHeight) {
        if (newWidth < MINIMUM_DIMENSION || newHeight < MINIMUM_DIMENSION) {
            throw new IllegalArgumentException("Map dimensions must be at least " + MINIMUM_DIMENSION);
        }
        width = newWidth;
        height = newHeight;
        terrain = new UiTerrain[width * height];
        for (int index = 0; index < terrain.length; index++) {
            terrain[index] = UiTerrain.OPEN;
        }
        int centerY = height / 2;
        spawn = new UiCell(0, centerY);
        endpoint = new UiCell(width - 1, centerY);
        checkpoints.clear();
        lastTrace = null;
        terminalFrame = -1;
        trajectoryBreak = false;
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

        List<Checkpoint> routeCheckpoints = new ArrayList<>(checkpoints.size());
        for (UiCell checkpoint : checkpoints) {
            routeCheckpoints.add(Checkpoint.move(toCorePoint(checkpoint.center())));
        }
        route = new Route(
                toCorePoint(spawn.center()),
                toCorePoint(endpoint.center()),
                routeCheckpoints,
                coreMovementMode(),
                allowDiagonalMove,
                true,
                false);
        UnitConfig config = movementMode == UiMovementMode.GROUND
                ? UnitConfig.normalGround(attributeSpeed)
                : UnitConfig.normalFlying(attributeSpeed);
        simulator = new PathfindingSimulator(map, route, config);
        lastTrace = null;
        globalFrame = 0L;
        if (resetTimeline) {
            terminalFrame = -1;
        }
        trajectoryBreak = false;
        if (isTerminalMode(simulator.unit().mode())) {
            terminalFrame = 0;
        }
        if (resetTimeline) {
            scenarioRevision++;
            timeline.clear();
            timeline.add(snapshot());
            publishTimeline();
        }
    }

    private void ensureTimelineInitialized() {
        if (timeline.isEmpty()) {
            timeline.add(snapshot());
            publishTimeline();
            if (isTerminalMode(simulator.unit().mode())) {
                terminalFrame = 0;
            }
        }
    }

    private void publishTimeline() {
        publishedTimeline = List.copyOf(timeline);
    }

    private PathMap activePathMap() {
        RouteProgress progress = simulator.unit().routeProgress();
        int checkpointIndex = progress.checkpointIndex();
        return !progress.completed() && checkpointIndex < route.checkpoints().size()
                ? simulator.pathMapForCheckpoint(checkpointIndex)
                : simulator.endpointPathMap();
    }

    private UiPoint currentTarget(PathMap activePathMap) {
        if (simulator.unit().routeProgress().completed()) {
            return toUiPoint(route.endpoint());
        }
        if (lastTrace != null && lastTrace.target() != null) {
            return toUiPoint(lastTrace.target());
        }
        return toUiPoint(activePathMap.target().center());
    }

    private List<UiPathSegment> pathSegments(PathMap activePathMap) {
        List<UiPathSegment> segments = new ArrayList<>();
        MovementMode coreMovementMode = coreMovementMode();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                TileCoord cell = new TileCoord(x, y);
                if (!map.passable(cell, coreMovementMode) || !activePathMap.reachable(cell)) {
                    continue;
                }
                TileCoord nextNode = activePathMap.nextNode(cell);
                if (nextNode != null && !nextNode.equals(cell)) {
                    segments.add(new UiPathSegment(toUiCell(cell), toUiCell(nextNode)));
                }
            }
        }
        return segments;
    }

    private List<UiTerrain> terrainView() {
        List<UiTerrain> result = new ArrayList<>(terrain.length);
        for (UiTerrain tile : terrain) {
            result.add(tile);
        }
        return result;
    }

    private List<UiPoint> checkpointPoints() {
        List<UiPoint> result = new ArrayList<>(checkpoints.size());
        for (UiCell checkpoint : checkpoints) {
            result.add(checkpoint.center());
        }
        return result;
    }

    private boolean isRouteCell(UiCell cell) {
        return cell.equals(spawn) || cell.equals(endpoint) || checkpoints.contains(cell);
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

    private MovementMode coreMovementMode() {
        return movementMode == UiMovementMode.GROUND ? MovementMode.GROUND : MovementMode.FLYING;
    }

    private static TileRule coreRule(UiTerrain terrain) {
        return switch (terrain) {
            case OPEN -> TileRule.open();
            case BOX -> TileRule.costlyObstacle(TileRule.BOX_COST);
            case PIT -> TileRule.costlyObstacle(TileRule.PIT_COST);
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
}
