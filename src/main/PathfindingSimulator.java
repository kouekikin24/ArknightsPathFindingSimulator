import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * UI-free deterministic simulator for one independently moving unit.
 * Global frame numbers are supplied by the caller so all units share the same
 * three-frame avoidance phase.
 */
public final class PathfindingSimulator {
    public static final int DEFAULT_TRACE_CAPACITY = 10_000;
    public static final float ENDPOINT_RADIUS = Checkpoint.DEFAULT_MOVE_RADIUS;

    private final GridMap map;
    private final Route route;
    private final UnitConfig config;
    private final UnitState unit;
    private final StageClock clock = new StageClock();
    private final PathMapCache pathMaps;
    private final AvoidanceCalculator avoidanceCalculator = new AvoidanceCalculator();
    private final MotionIntegrator motionIntegrator = new MotionIntegrator();
    private final CollisionResolver collisionResolver = new CollisionResolver();
    private final ArrayDeque<FrameTrace> trace;
    private final int traceCapacity;
    private final Consumer<FrameTrace> traceListener;
    private final List<RouteGoal> routeGoals;
    private List<float[]> remainingDistances;
    private long preparedRouteDistanceMapVersion = Long.MIN_VALUE;
    private long lastGlobalFrame = Long.MIN_VALUE;
    private long droppedTraceFrames;
    private int frame;

    public PathfindingSimulator(GridMap map, Route route, UnitConfig config) {
        this(map, route, config, new PathMapCache(), DEFAULT_TRACE_CAPACITY, null);
    }

    public PathfindingSimulator(GridMap map, Route route, UnitConfig config, int traceCapacity) {
        this(map, route, config, new PathMapCache(), traceCapacity, null);
    }

    public PathfindingSimulator(GridMap map, Route route, UnitConfig config,
                                int traceCapacity, Consumer<FrameTrace> traceListener) {
        this(map, route, config, new PathMapCache(), traceCapacity, traceListener);
    }

    public PathfindingSimulator(GridMap map, Route route, UnitConfig config, PathMapCache pathMaps,
                                int traceCapacity, Consumer<FrameTrace> traceListener) {
        this.map = Objects.requireNonNull(map, "Map is required");
        this.route = Objects.requireNonNull(route, "Route is required");
        this.config = Objects.requireNonNull(config, "Unit config is required");
        this.pathMaps = Objects.requireNonNull(pathMaps, "Path map cache is required");
        if (traceCapacity <= 0) {
            throw new IllegalArgumentException("Trace capacity must be positive");
        }
        this.traceCapacity = traceCapacity;
        this.trace = new ArrayDeque<>(traceCapacity);
        this.traceListener = traceListener;
        this.unit = new UnitState(route, config);
        this.routeGoals = collectRouteGoals();
        activateCurrentCheckpoint();
    }

    public UnitState unit() {
        return unit;
    }

    public StageClock clock() {
        return clock;
    }

    public int frame() {
        return frame;
    }

    public long lastGlobalFrame() {
        return lastGlobalFrame;
    }

    public long droppedTraceFrames() {
        return droppedTraceFrames;
    }

    public List<FrameTrace> trace() {
        return List.copyOf(trace);
    }

    public PathMap endpointPathMap() {
        return pathMapForGoal(routeGoals.size() - 1);
    }

    public PathMap pathMapForCheckpoint(int checkpointIndex) {
        int goalIndex = routeGoalIndexForCheckpoint(checkpointIndex);
        if (goalIndex < 0) {
            Checkpoint checkpoint = route.checkpoints().get(checkpointIndex);
            throw new IllegalArgumentException("Checkpoint does not own a path map: " + checkpoint.type());
        }
        return pathMapForGoal(goalIndex);
    }

    public boolean checkpointOwnsPathMap(int checkpointIndex) {
        return checkpointIndex >= 0
                && checkpointIndex < route.checkpoints().size()
                && routeGoalIndexForCheckpoint(checkpointIndex) >= 0;
    }

    public float remainingRouteDistanceForCheckpoint(int checkpointIndex, TileCoord coordinate) {
        return remainingDistanceForGoal(routeGoalIndexForCheckpoint(checkpointIndex), coordinate);
    }

    public float remainingRouteDistanceToEndpoint(TileCoord coordinate) {
        return remainingDistanceForGoal(routeGoals.size() - 1, coordinate);
    }

    /**
     * Compatibility entry point for isolated callers. Multi-unit callers must
     * use tick(long) with the stage's shared frame number.
     */
    @Deprecated
    public FrameTrace tick() {
        return tick(lastGlobalFrame == Long.MIN_VALUE ? 0L : lastGlobalFrame + 1L);
    }

    /**
     * Holds the unit motionless (keeping inertia) starting at the supplied
     * frame for the given seconds, rounded to whole frames.
     */
    public void stun(long globalFrame, float seconds) {
        requireInjectionFrame(globalFrame);
        requireDuration(seconds);
        requireInjectionEligible();
        unit.setMode(UnitMode.STUNNED);
        unit.setTimedModeUntilGlobalFrame(globalFrame + durationFrames(seconds));
    }

    /**
     * Pushes the unit at a constant velocity for the given seconds starting at
     * the supplied frame. Terrain collision still applies; after the duration
     * the unit returns to steering-driven movement.
     */
    public void displace(long globalFrame, Vec2f velocity, float seconds) {
        requireInjectionFrame(globalFrame);
        requireDuration(seconds);
        if (velocity == null || !Float.isFinite(velocity.x()) || !Float.isFinite(velocity.y())) {
            throw new IllegalArgumentException("Displacement velocity must be finite");
        }
        requireInjectionEligible();
        unit.setMode(UnitMode.DISPLACED);
        unit.setDisplacementVelocity(velocity);
        unit.setTimedModeUntilGlobalFrame(globalFrame + durationFrames(seconds));
    }

    /** Binds or releases the unit; a bound unit skips integration in every moving mode. */
    public void setBound(boolean bound) {
        unit.setBound(bound);
    }

    private void requireInjectionFrame(long globalFrame) {
        if (globalFrame < 0L || (lastGlobalFrame != Long.MIN_VALUE && globalFrame != lastGlobalFrame + 1L)) {
            throw new IllegalArgumentException(
                    "Injection frame must be the next frame after " + lastGlobalFrame);
        }
    }

    private static void requireDuration(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0f) {
            throw new IllegalArgumentException("Duration must be finite and non-negative");
        }
    }

    private void requireInjectionEligible() {
        if (unit.mode() == UnitMode.BLOCKED || unit.mode() == UnitMode.COMPLETED
                || unit.mode() == UnitMode.VANISHED) {
            throw new IllegalStateException("Cannot inject into a unit in " + unit.mode());
        }
    }

    private void expireTimedMode(long globalFrame) {
        if ((unit.mode() == UnitMode.STUNNED || unit.mode() == UnitMode.DISPLACED)
                && globalFrame >= unit.timedModeUntilGlobalFrame()) {
            unit.setDisplacementVelocity(Vec2f.ZERO);
            unit.setTimedModeUntilGlobalFrame(Long.MIN_VALUE);
            unit.setMode(UnitMode.MOVE);
        }
    }

    private static long durationFrames(float seconds) {
        return Math.max(1L, Math.round(seconds / F32.DT));
    }

    public FrameTrace tick(long globalFrame) {
        validateGlobalFrame(globalFrame);
        expireTimedMode(globalFrame);

        Vec2f entityBefore = unit.entityPosition();
        Vec2f cursorBefore = unit.cursorPosition();
        Vec2f inertiaBefore = unit.inertiaVelocity();
        UnitMode modeBefore = unit.mode();
        Checkpoint checkpointBefore = unit.routeProgress().current(route);
        int checkpointIndexBefore = unit.routeProgress().checkpointIndex();
        TileCoord cursorTile = TileCoord.fromPosition(cursorBefore);
        boolean outsideMap = !map.contains(cursorTile);
        Navigation navigation = resolveFrameNavigation(checkpointBefore, cursorTile, outsideMap);
        boolean recomputedAvoidance = refreshAvoidance(globalFrame, navigation);
        Vec2f requestedDisplacement = Vec2f.ZERO;
        Vec2f appliedDisplacement = Vec2f.ZERO;

        if (unit.mode() == UnitMode.MOVE) {
            if (!outsideMap && navigation.unreachable()) {
                unit.setMode(UnitMode.BLOCKED);
            } else if (!unit.bound() && navigation.target() != null) {
                MotionIntegrator.MotionResult result = motionIntegrator.integrate(
                        config, unit, navigation.givenDirection());
                requestedDisplacement = result.requestedDisplacement();
                CollisionResult collision = collisionResolver.resolve(
                        map, route.movementMode(), unit, requestedDisplacement, result.velocity(),
                        !outsideMap);
                appliedDisplacement = collision.appliedDisplacement();
                unit.setInertiaVelocity(collision.inertiaVelocity());
                unit.translate(appliedDisplacement);
            }
        } else if (unit.mode() == UnitMode.DISPLACED && !unit.bound()) {
            Vec2f velocity = unit.displacementVelocity();
            requestedDisplacement = velocity.multiply(F32.DT);
            CollisionResult collision = collisionResolver.resolve(
                    map, route.movementMode(), unit, requestedDisplacement, velocity, !outsideMap);
            appliedDisplacement = collision.appliedDisplacement();
            unit.setInertiaVelocity(collision.inertiaVelocity());
            unit.translate(appliedDisplacement);
        }

        String transition = updateCheckpointAndEndpoint();
        if (modeBefore == UnitMode.MOVE && unit.mode() == UnitMode.BLOCKED && transition.isEmpty()) {
            transition = navigation.unreachableTarget().diagnostic();
        }
        FrameTrace frameTrace = new FrameTrace(
                frame,
                globalFrame,
                checkpointIndexBefore,
                checkpointBefore == null ? null : checkpointBefore.type(),
                modeBefore,
                unit.mode(),
                entityBefore,
                unit.entityPosition(),
                cursorBefore,
                unit.cursorPosition(),
                cursorTile,
                navigation.nextNode(),
                navigation.target(),
                navigation.givenDirection(),
                recomputedAvoidance,
                unit.cachedAvoidance(),
                inertiaBefore,
                unit.inertiaVelocity(),
                requestedDisplacement,
                appliedDisplacement,
                transition);
        retainTrace(frameTrace);
        lastGlobalFrame = globalFrame;
        frame++;
        clock.tick();
        return frameTrace;
    }

    private void validateGlobalFrame(long globalFrame) {
        if (globalFrame < 0L) {
            throw new IllegalArgumentException("Global frame must be non-negative");
        }
        if (lastGlobalFrame != Long.MIN_VALUE && globalFrame != lastGlobalFrame + 1L) {
            throw new IllegalArgumentException("Global frames must be consecutive: expected "
                    + (lastGlobalFrame + 1L) + ", got " + globalFrame);
        }
        if (frame == Integer.MAX_VALUE) {
            throw new IllegalStateException("Local audit frame counter would overflow");
        }
    }

    private void retainTrace(FrameTrace frameTrace) {
        if (trace.size() == traceCapacity) {
            trace.removeFirst();
            droppedTraceFrames++;
        }
        trace.addLast(frameTrace);
        if (traceListener != null) {
            traceListener.accept(frameTrace);
        }
    }

    private Navigation resolveFrameNavigation(Checkpoint checkpoint, TileCoord cursorTile,
                                              boolean outsideMap) {
        if (unit.mode() != UnitMode.MOVE) {
            return Navigation.none();
        }
        if (outsideMap) {
            Vec2f destination = mapInteriorDestination();
            return new Navigation(destination,
                    destination.subtract(unit.cursorPosition()).normalized(),
                    null, null, UnreachableTarget.NONE);
        }
        return resolveNavigation(checkpoint, cursorTile);
    }

    private boolean refreshAvoidance(long globalFrame, Navigation navigation) {
        if (!refreshesAvoidance(unit.mode()) || globalFrame % 3L != 0L) {
            return false;
        }
        unit.setCachedAvoidance(avoidanceCalculator.calculate(
                map, route.movementMode(), config, unit, navigation.givenDirection()), globalFrame);
        return true;
    }

    private static boolean refreshesAvoidance(UnitMode mode) {
        return switch (mode) {
            case MOVE, STUNNED, DISPLACED -> true;
            case BLOCKED, VANISHED, COMPLETED -> false;
        };
    }

    private Vec2f mapInteriorDestination() {
        Vec2f cursor = unit.cursorPosition();
        return new Vec2f(
                F32.clamp(cursor.x(), 0.5f, map.width() - 0.5f),
                F32.clamp(cursor.y(), 0.5f, map.height() - 0.5f));
    }

    private Navigation resolveNavigation(Checkpoint checkpoint, TileCoord currentTile) {
        if (checkpoint != null && !checkpoint.type().isMovement()) {
            return Navigation.none();
        }

        Vec2f targetPoint = checkpoint == null ? route.endpoint() : checkpoint.point();
        TileCoord targetTile = TileCoord.fromPosition(targetPoint);
        PathMap pathMap = pathMaps.get(map, targetTile, route.movementMode(), route.allowDiagonalMove());
        if (!map.passable(currentTile, route.movementMode()) || !pathMap.reachable(currentTile)) {
            return new Navigation(targetPoint, Vec2f.ZERO, null, pathMap,
                    checkpoint == null ? UnreachableTarget.ENDPOINT : UnreachableTarget.MOVE);
        }

        int checkpointIndex = checkpoint == null
                ? route.checkpoints().size()
                : unit.routeProgress().checkpointIndex();
        if (!unit.visitStateMatches(targetTile, checkpointIndex)) {
            unit.resetVisitState(targetTile, checkpointIndex);
        }
        unit.observeCursorTile(currentTile);
        TileCoord nextNode = pathMap.nextNode(currentTile);
        if (nextNode == null) {
            return new Navigation(targetPoint, Vec2f.ZERO, null, pathMap,
                    checkpoint == null ? UnreachableTarget.ENDPOINT : UnreachableTarget.MOVE);
        }

        Vec2f steeringTarget = nextNode.equals(targetTile) ? targetPoint : nextNode.center();
        steeringTarget = applyVisitPolicy(pathMap, currentTile, nextNode, targetTile, steeringTarget);
        Vec2f direction = steeringTarget.subtract(unit.cursorPosition()).normalized();
        return new Navigation(steeringTarget, direction, nextNode, pathMap, UnreachableTarget.NONE);
    }

    private Vec2f applyVisitPolicy(PathMap pathMap, TileCoord currentTile, TileCoord nextNode,
                                   TileCoord targetTile, Vec2f defaultTarget) {
        boolean tileCenter = config.visitEveryTileCenter();
        boolean nodeCenter = !tileCenter && config.visitEveryNodeCenter();
        boolean stableNode = !tileCenter && !nodeCenter && config.visitEveryNodeStably();
        if (!tileCenter && !nodeCenter && !stableNode) {
            return defaultTarget;
        }

        float radius = stableNode ? 0.25f : Checkpoint.DEFAULT_MOVE_RADIUS;
        if (unit.cursorPosition().distanceTo(currentTile.center()) <= radius) {
            unit.passedTileCenters().add(currentTile);
        }

        if (tileCenter && !unit.passedTileCenters().contains(currentTile)) {
            return currentTile.center();
        }

        if (stableNode || nodeCenter) {
            TileCoord previous = unit.previousDistinctCursorTile();
            TileCoord expectedCurrent = previous == null ? null : pathMap.nextNode(previous);
            boolean enteredExpectedNextNode = currentTile.equals(expectedCurrent);
            if (enteredExpectedNextNode && !unit.passedTileCenters().contains(currentTile)) {
                return currentTile.center();
            }
            if (nodeCenter && unit.passedTileCenters().contains(nextNode)) {
                return defaultTarget;
            }
        }
        return defaultTarget;
    }

    private String updateCheckpointAndEndpoint() {
        if (unit.routeProgress().completed() || unit.mode() == UnitMode.BLOCKED) {
            return "";
        }
        StringBuilder transitions = new StringBuilder();
        int safety = route.checkpoints().size() + 2;
        while (safety-- > 0) {
            Checkpoint checkpoint = unit.routeProgress().current(route);
            if (checkpoint == null) {
                break;
            }
            if (route.ignoreAllButMoveCp() && !includedWhenIgnoring(checkpoint)) {
                appendTransition(transitions, "skip " + checkpoint.type());
                advanceCheckpoint();
                continue;
            }
            if (!checkpointComplete(checkpoint)) {
                break;
            }
            appendTransition(transitions, "complete " + checkpoint.type());
            if (advanceCheckpoint()) {
                break;
            }
        }

        boolean canCompleteEndpoint = unit.mode() != UnitMode.VANISHED
                && (!route.visitEveryCheckpoint() || unit.routeProgress().current(route) == null);
        if (canCompleteEndpoint && unit.cursorPosition().distanceTo(route.endpoint()) <= ENDPOINT_RADIUS) {
            unit.routeProgress().markCompleted();
            unit.setMode(UnitMode.COMPLETED);
            appendTransition(transitions, "complete ENDPOINT");
        }
        return transitions.toString();
    }

    private boolean checkpointComplete(Checkpoint checkpoint) {
        return switch (checkpoint.type()) {
            case MOVE, PATROL_MOVE -> movementCheckpointComplete(checkpoint);
            case WAIT_FOR_SECONDS -> clock.playTime() - unit.routeProgress().enteredPlayTime() >= checkpoint.value();
            case WAIT_FOR_PLAY_TIME -> clock.playTime() >= checkpoint.value();
            case WAIT_CURRENT_FRAGMENT_TIME -> clock.fragmentTime() >= checkpoint.value();
            case WAIT_CURRENT_WAVE_TIME -> clock.waveTime() >= checkpoint.value();
            case DISAPPEAR, APPEAR_AT_POS, ALERT -> true;
            case WAIT_BOSSRUSH_WAVE -> clock.bossRushArea() >= checkpoint.area();
        };
    }

    private boolean movementCheckpointComplete(Checkpoint checkpoint) {
        TileCoord cursorTile = TileCoord.fromPosition(unit.cursorPosition());
        return map.contains(cursorTile)
                && unit.cursorPosition().distanceTo(checkpoint.point()) <= checkpoint.radius();
    }

    private boolean includedWhenIgnoring(Checkpoint checkpoint) {
        return checkpoint.type() == CheckpointType.MOVE
                || checkpoint.type() == CheckpointType.PATROL_MOVE
                || checkpoint.type() == CheckpointType.DISAPPEAR
                || checkpoint.type() == CheckpointType.APPEAR_AT_POS;
    }

    private boolean advanceCheckpoint() {
        boolean looped = unit.routeProgress().advance(route, clock);
        activateCurrentCheckpoint();
        return looped;
    }

    private void activateCurrentCheckpoint() {
        while (true) {
            Checkpoint current = unit.routeProgress().current(route);
            if (current == null || !route.ignoreAllButMoveCp() || includedWhenIgnoring(current)) {
                break;
            }
            unit.routeProgress().advance(route, clock);
        }
        enterCurrentCheckpoint();
    }

    private void enterCurrentCheckpoint() {
        unit.routeProgress().enterAt(clock);
        Checkpoint current = unit.routeProgress().current(route);
        if (current == null) {
            return;
        }
        switch (current.type()) {
            case DISAPPEAR -> unit.setMode(UnitMode.VANISHED);
            case APPEAR_AT_POS -> {
                // Portal relocation deliberately retains the inertia vector unchanged.
                unit.relocateCursor(current.point());
                unit.setMode(UnitMode.MOVE);
            }
            case ALERT -> unit.alertShown();
            default -> {
                // Movement and wait checkpoints have no entry side effect.
            }
        }
    }

    private List<RouteGoal> collectRouteGoals() {
        List<RouteGoal> goals = new ArrayList<>();
        for (int index = 0; index < route.checkpoints().size(); index++) {
            Checkpoint checkpoint = route.checkpoints().get(index);
            if (checkpoint.type().isMovement()) {
                goals.add(new RouteGoal(index, checkpoint.point(), route.targetTile(checkpoint)));
            }
        }
        goals.add(new RouteGoal(route.checkpoints().size(), route.endpoint(), route.endpointTile()));
        return List.copyOf(goals);
    }

    private void ensureRouteDistancesCurrent() {
        if (remainingDistances != null && preparedRouteDistanceMapVersion == map.version()) {
            return;
        }
        List<float[]> rebuilt = new ArrayList<>(routeGoals.size());
        for (int index = 0; index < routeGoals.size(); index++) {
            rebuilt.add(new float[map.width() * map.height()]);
        }
        boolean loopingPatrol = route.hasTerminalPatrolLoop();
        for (int index = routeGoals.size() - 1; index >= 0; index--) {
            RouteGoal goal = routeGoals.get(index);
            PathMap pathMap = pathMapForGoal(goal);
            float continuation = 0f;
            if (loopingPatrol) {
                continuation = Float.POSITIVE_INFINITY;
            } else if (index + 1 < routeGoals.size()) {
                RouteGoal nextGoal = routeGoals.get(index + 1);
                Vec2f continuationStart = continuationStart(goal.checkpointIndex(), nextGoal.checkpointIndex(), goal.point());
                TileCoord continuationTile = TileCoord.fromPosition(continuationStart);
                continuation = map.contains(continuationTile)
                        ? rebuilt.get(index + 1)[map.index(continuationTile)]
                        : Float.POSITIVE_INFINITY;
            }
            float[] values = rebuilt.get(index);
            for (int y = 0; y < map.height(); y++) {
                for (int x = 0; x < map.width(); x++) {
                    TileCoord coordinate = new TileCoord(x, y);
                    float targetDistance = pathMap.distanceToTarget(coordinate);
                    values[map.index(coordinate)] = Float.isInfinite(targetDistance) || Float.isInfinite(continuation)
                            ? Float.POSITIVE_INFINITY
                            : targetDistance + continuation;
                }
            }
        }
        remainingDistances = List.copyOf(rebuilt);
        preparedRouteDistanceMapVersion = map.version();
    }

    private Vec2f continuationStart(int currentCheckpointIndex, int nextGoalCheckpointIndex, Vec2f defaultStart) {
        Vec2f result = defaultStart;
        int stopExclusive = Math.min(nextGoalCheckpointIndex, route.checkpoints().size());
        for (int index = currentCheckpointIndex + 1; index < stopExclusive; index++) {
            Checkpoint checkpoint = route.checkpoints().get(index);
            if (checkpoint.type() == CheckpointType.APPEAR_AT_POS) {
                result = checkpoint.point();
            }
        }
        return result;
    }

    private float remainingDistanceForGoal(int goalIndex, TileCoord coordinate) {
        if (goalIndex < 0 || goalIndex >= routeGoals.size()) {
            throw new IllegalArgumentException("Checkpoint does not own a route distance");
        }
        ensureRouteDistancesCurrent();
        return remainingDistances.get(goalIndex)[map.index(coordinate)];
    }

    private int routeGoalIndexForCheckpoint(int checkpointIndex) {
        for (int index = 0; index < routeGoals.size() - 1; index++) {
            if (routeGoals.get(index).checkpointIndex() == checkpointIndex) {
                return index;
            }
        }
        return -1;
    }

    private PathMap pathMapForGoal(int goalIndex) {
        return pathMapForGoal(routeGoals.get(goalIndex));
    }

    private PathMap pathMapForGoal(RouteGoal goal) {
        return pathMaps.get(map, goal.tile(), route.movementMode(), route.allowDiagonalMove());
    }

    private static void appendTransition(StringBuilder target, String value) {
        if (!target.isEmpty()) {
            target.append(", ");
        }
        target.append(value);
    }

    private record Navigation(Vec2f target, Vec2f givenDirection, TileCoord nextNode,
                              PathMap pathMap, UnreachableTarget unreachableTarget) {
        boolean unreachable() {
            return unreachableTarget != UnreachableTarget.NONE;
        }

        static Navigation none() {
            return new Navigation(null, Vec2f.ZERO, null, null, UnreachableTarget.NONE);
        }
    }

    private enum UnreachableTarget {
        NONE(""),
        MOVE("blocked unreachable MOVE"),
        ENDPOINT("blocked unreachable ENDPOINT");

        private final String diagnostic;

        UnreachableTarget(String diagnostic) {
            this.diagnostic = diagnostic;
        }

        String diagnostic() {
            return diagnostic;
        }
    }

    private record RouteGoal(int checkpointIndex, Vec2f point, TileCoord tile) {
    }
}
