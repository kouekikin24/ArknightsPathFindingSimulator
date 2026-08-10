import java.util.ArrayList;
import java.util.List;

/**
 * UI-free deterministic simulator. It owns a single unit because route state,
 * movement state, and frame traces are deliberately explicit; callers may
 * create one simulator per independently simulated enemy.
 */
public final class PathfindingSimulator {
    private final GridMap map;
    private final Route route;
    private final UnitConfig config;
    private final UnitState unit;
    private final StageClock clock = new StageClock();
    private final PathMapCache pathMaps = new PathMapCache();
    private final AvoidanceCalculator avoidanceCalculator = new AvoidanceCalculator();
    private final MotionIntegrator motionIntegrator = new MotionIntegrator();
    private final CollisionResolver collisionResolver = new CollisionResolver();
    private final List<FrameTrace> trace = new ArrayList<>();
    private int frame;

    public PathfindingSimulator(GridMap map, Route route, UnitConfig config) {
        this.map = map;
        this.route = route;
        this.config = config;
        this.unit = new UnitState(route, config);
        activateCurrentCheckpoint();
        prepareRouteMaps();
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

    public List<FrameTrace> trace() {
        return List.copyOf(trace);
    }

    public PathMap endpointPathMap() {
        return pathMaps.get(map, route.endpointTile(), route.movementMode(), route.allowDiagonalMove());
    }

    public PathMap pathMapForCheckpoint(int checkpointIndex) {
        Checkpoint checkpoint = route.checkpoints().get(checkpointIndex);
        if (!checkpoint.type().isMovement()) {
            throw new IllegalArgumentException("Checkpoint does not own a path map: " + checkpoint.type());
        }
        return pathMaps.get(map, route.targetTile(checkpoint), route.movementMode(), route.allowDiagonalMove());
    }

    public FrameTrace tick() {
        Vec2f entityBefore = unit.entityPosition();
        Vec2f cursorBefore = unit.cursorPosition();
        Vec2f inertiaBefore = unit.inertiaVelocity();
        UnitMode modeBefore = unit.mode();
        Checkpoint checkpointBefore = unit.routeProgress().current(route);
        int checkpointIndexBefore = unit.routeProgress().checkpointIndex();
        TileCoord cursorTile = TileCoord.fromPosition(cursorBefore);
        Navigation navigation = Navigation.none();
        boolean recomputedAvoidance = false;
        Vec2f requestedDisplacement = Vec2f.ZERO;
        Vec2f appliedDisplacement = Vec2f.ZERO;

        if (unit.mode() == UnitMode.MOVE) {
            if (!map.contains(cursorTile)) {
                appliedDisplacement = moveTowardMapInterior();
            } else {
                navigation = resolveNavigation(checkpointBefore);
                float theoreticalSpeed = config.theoreticalSpeed();
                if (navigation.target() != null
                        && unit.cursorPosition().distanceTo(navigation.target()) <= theoreticalSpeed * F32.DT) {
                    Vec2f snapDelta = navigation.target().subtract(unit.cursorPosition());
                    unit.translate(snapDelta);
                    appliedDisplacement = snapDelta;
                } else if (!unit.bound()) {
                    if (frame % 3 == 0) {
                        unit.setCachedAvoidance(avoidanceCalculator.calculate(
                                map, route.movementMode(), config, unit, navigation.givenDirection()), frame);
                        recomputedAvoidance = true;
                    }
                    MotionIntegrator.MotionResult result = motionIntegrator.integrate(config, unit, navigation.givenDirection());
                    requestedDisplacement = result.requestedDisplacement();
                    appliedDisplacement = collisionResolver.resolve(map, route.movementMode(), unit, requestedDisplacement);
                    unit.setInertiaVelocity(result.velocity());
                    unit.translate(appliedDisplacement);
                }
            }
        }

        String transition = updateCheckpointAndEndpoint();
        FrameTrace frameTrace = new FrameTrace(
                frame,
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
        trace.add(frameTrace);
        frame++;
        clock.tick();
        return frameTrace;
    }

    private Vec2f moveTowardMapInterior() {
        Vec2f cursor = unit.cursorPosition();
        Vec2f destination = new Vec2f(
                F32.clamp(cursor.x(), 0.5f, map.width() - 0.5f),
                F32.clamp(cursor.y(), 0.5f, map.height() - 0.5f));
        Vec2f toInside = destination.subtract(cursor);
        if (toInside.length() <= F32.EPSILON) {
            return Vec2f.ZERO;
        }
        float distance = F32.min(config.theoreticalSpeed() * F32.DT, toInside.length());
        Vec2f delta = toInside.normalized().multiply(distance);
        unit.translate(delta);
        return delta;
    }

    private Navigation resolveNavigation(Checkpoint checkpoint) {
        if (checkpoint != null && !checkpoint.type().isMovement()) {
            return Navigation.none();
        }

        Vec2f targetPoint = checkpoint == null ? route.endpoint() : checkpoint.point();
        TileCoord targetTile = TileCoord.fromPosition(targetPoint);
        PathMap pathMap = pathMaps.get(map, targetTile, route.movementMode(), route.allowDiagonalMove());
        TileCoord currentTile = TileCoord.fromPosition(unit.cursorPosition());
        if (!map.contains(currentTile) || !map.passable(currentTile, route.movementMode()) || !pathMap.reachable(currentTile)) {
            return new Navigation(targetPoint, Vec2f.ZERO, null, pathMap);
        }

        if (!targetTile.equals(unit.visitGoalTile())) {
            unit.resetVisitState(targetTile);
        }
        TileCoord nextNode = pathMap.nextNode(currentTile);
        if (nextNode == null) {
            return new Navigation(targetPoint, Vec2f.ZERO, null, pathMap);
        }

        Vec2f steeringTarget = nextNode.equals(targetTile) ? targetPoint : nextNode.center();
        steeringTarget = applyVisitPolicy(pathMap, currentTile, nextNode, targetTile, steeringTarget);
        Vec2f direction = steeringTarget.subtract(unit.cursorPosition()).normalized();
        unit.setPreviousCursorTile(currentTile);
        return new Navigation(steeringTarget, direction, nextNode, pathMap);
    }

    private Vec2f applyVisitPolicy(PathMap pathMap, TileCoord currentTile, TileCoord nextNode,
                                   TileCoord targetTile, Vec2f defaultTarget) {
        boolean tileCenter = config.visitEveryTileCenter();
        boolean nodeCenter = !tileCenter && config.visitEveryNodeCenter();
        boolean stableNode = !tileCenter && !nodeCenter
                && (config.visitEveryNodeStably() || route.hasNoCheckpoints());
        if (!tileCenter && !nodeCenter && !stableNode) {
            return defaultTarget;
        }

        float radius = stableNode ? 0.25f : 0.05f;
        if (unit.cursorPosition().distanceTo(currentTile.center()) <= radius) {
            unit.passedTileCenters().add(currentTile);
        }

        if (tileCenter && !unit.passedTileCenters().contains(currentTile)) {
            return currentTile.center();
        }

        if (stableNode || nodeCenter) {
            TileCoord previous = unit.previousCursorTile();
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
            advanceCheckpoint();
        }

        boolean canCompleteEndpoint = !route.visitEveryCheckpoint() || unit.routeProgress().current(route) == null;
        if (canCompleteEndpoint && unit.cursorPosition().distanceTo(route.endpoint()) <= 0.05f) {
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
        if (unit.cursorPosition().distanceTo(checkpoint.point()) <= checkpoint.radius()) {
            return true;
        }
        TileCoord cursorTile = TileCoord.fromPosition(unit.cursorPosition());
        PathMap mapForCheckpoint = pathMaps.get(
                map, route.targetTile(checkpoint), route.movementMode(), route.allowDiagonalMove());
        return !map.contains(cursorTile) || !mapForCheckpoint.reachable(cursorTile);
    }

    private boolean includedWhenIgnoring(Checkpoint checkpoint) {
        return checkpoint.type() == CheckpointType.MOVE
                || checkpoint.type() == CheckpointType.PATROL_MOVE
                || checkpoint.type() == CheckpointType.DISAPPEAR
                || checkpoint.type() == CheckpointType.APPEAR_AT_POS;
    }

    private void advanceCheckpoint() {
        unit.routeProgress().advance(route, clock);
        activateCurrentCheckpoint();
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
                if (unit.mode() == UnitMode.VANISHED) {
                    // A non-appearance checkpoint keeps the unit vanished.
                }
            }
        }
    }

    private void prepareRouteMaps() {
        List<RouteGoal> goals = new ArrayList<>();
        for (int index = 0; index < route.checkpoints().size(); index++) {
            Checkpoint checkpoint = route.checkpoints().get(index);
            if (checkpoint.type().isMovement()) {
                goals.add(new RouteGoal(index, checkpoint.point(), route.targetTile(checkpoint)));
            }
        }
        goals.add(new RouteGoal(route.checkpoints().size(), route.endpoint(), route.endpointTile()));

        List<PathMap> maps = new ArrayList<>(goals.size());
        for (RouteGoal goal : goals) {
            maps.add(pathMaps.get(map, goal.tile(), route.movementMode(), route.allowDiagonalMove()));
        }

        PathMap endpointMap = maps.getLast();
        copyTargetDistanceToEnd(endpointMap, 0f);

        for (int index = goals.size() - 2; index >= 0; index--) {
            RouteGoal goal = goals.get(index);
            RouteGoal nextGoal = goals.get(index + 1);
            Vec2f continuationStart = continuationStart(goal.checkpointIndex(), nextGoal.checkpointIndex(), goal.point());
            PathMap nextMap = maps.get(index + 1);
            TileCoord continuationTile = TileCoord.fromPosition(continuationStart);
            float continuationDistance = map.contains(continuationTile)
                    ? nextMap.distanceToEnd(continuationTile)
                    : Float.POSITIVE_INFINITY;
            copyTargetDistanceToEnd(maps.get(index), continuationDistance);
        }
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

    private void copyTargetDistanceToEnd(PathMap pathMap, float continuationDistance) {
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                TileCoord coordinate = new TileCoord(x, y);
                float targetDistance = pathMap.distanceToTarget(coordinate);
                pathMap.setDistanceToEnd(coordinate,
                        Float.isInfinite(targetDistance) || Float.isInfinite(continuationDistance)
                                ? Float.POSITIVE_INFINITY
                                : targetDistance + continuationDistance);
            }
        }
    }

    private static void appendTransition(StringBuilder target, String value) {
        if (!target.isEmpty()) {
            target.append(", ");
        }
        target.append(value);
    }

    private record Navigation(Vec2f target, Vec2f givenDirection, TileCoord nextNode, PathMap pathMap) {
        static Navigation none() {
            return new Navigation(null, Vec2f.ZERO, null, null);
        }
    }

    private record RouteGoal(int checkpointIndex, Vec2f point, TileCoord tile) {
    }
}
