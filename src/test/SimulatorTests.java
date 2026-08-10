import java.util.List;
import java.util.Arrays;

/** Dependency-free executable regression suite. */
public final class SimulatorTests {
    private static int passed;

    public static void main(String[] args) {
        run("tile centers and float drift", SimulatorTests::tileCentersAndFloatDrift);
        run("SPFA preserves up-right-down-left tie", SimulatorTests::spfaTieOrder);
        run("modified Bresenham blocks corner cutting", SimulatorTests::modifiedBresenhamCorner);
        run("modified Bresenham checks narrow bands", SimulatorTests::modifiedBresenhamNarrowBand);
        run("nextNode smoothing is ordered and in place", SimulatorTests::inPlaceNextNodeSmoothing);
        run("two checkpoint inertia turn", SimulatorTests::twoCheckpointInertiaTurn);
        run("avoidance refreshes on global modulo three", SimulatorTests::avoidanceRefreshCadence);
        run("portal preserves inertia velocity", SimulatorTests::portalPreservesInertia);
        run("collision reflects incoming displacement", SimulatorTests::collisionReflection);
        run("ignored checkpoints have no side effects", SimulatorTests::ignoredCheckpoints);
        run("route maps accumulate endpoint distance across portals", SimulatorTests::routeDistanceAcrossPortal);
        System.out.println("Passed " + passed + " simulator tests.");
    }

    private static void tileCentersAndFloatDrift() {
        equal(new TileCoord(0, 0), TileCoord.fromPosition(new Vec2f(0.5f, 0.5f)), "first center");
        equal(new TileCoord(0, 0), TileCoord.fromPosition(new Vec2f(0.99999994f, 0.5f)), "left-side f32 drift");
        equal(new TileCoord(1, 0), TileCoord.fromPosition(new Vec2f(1.00000012f, 0.5f)), "right-side f32 drift");
        equal(new TileCoord(1, 2), TileCoord.fromPosition(new Vec2f(1.5f, 2.5f)), "tile center");
    }

    private static void spfaTieOrder() {
        GridMap map = new GridMap(3, 3);
        PathMap path = new PathMapBuilder().build(map, new TileCoord(1, 1), MovementMode.GROUND, true);
        equal(new TileCoord(1, 0), path.rawNextNode(new TileCoord(0, 0)),
                "source should keep the first UP, RIGHT, DOWN, LEFT relaxation");
        equal(2f, path.distanceToTarget(new TileCoord(0, 0)), 0.00001f, "grid distance");
    }

    private static void modifiedBresenhamCorner() {
        GridMap map = new GridMap(3, 3);
        truth(ModifiedBresenham.canLink(map, MovementMode.GROUND, new TileCoord(0, 0), new TileCoord(2, 2)),
                "open diagonal should link");
        map.setRule(new TileCoord(1, 0), TileRule.costlyObstacle(TileRule.BOX_COST));
        falsity(ModifiedBresenham.canLink(map, MovementMode.GROUND, new TileCoord(0, 0), new TileCoord(2, 2)),
                "the extra corner cell must block diagonal corner cutting");
    }

    private static void modifiedBresenhamNarrowBand() {
        GridMap map = new GridMap(2, 4);
        truth(ModifiedBresenham.canLink(map, MovementMode.GROUND, new TileCoord(0, 0), new TileCoord(1, 3)),
                "open narrow band should link");
        // This cell is outside the ordinary one-cell Bresenham trace but inside the documented 2xN band.
        map.setRule(new TileCoord(1, 0), TileRule.costlyObstacle(TileRule.BOX_COST));
        falsity(ModifiedBresenham.canLink(map, MovementMode.GROUND, new TileCoord(0, 0), new TileCoord(1, 3)),
                "one-tile horizontal difference must inspect the full two-column band");
    }

    private static void inPlaceNextNodeSmoothing() {
        GridMap map = new GridMap(3, 2);
        int[] next = new int[6];
        Arrays.fill(next, -1);
        TileCoord a = new TileCoord(1, 0);
        TileCoord b = new TileCoord(0, 1);
        TileCoord c = new TileCoord(1, 1);
        TileCoord d = new TileCoord(2, 1);
        next[map.index(a)] = map.index(b);
        next[map.index(b)] = map.index(c);
        next[map.index(c)] = map.index(d);
        next[map.index(d)] = map.index(d);

        NextNodeSmoother.smoothDiagonal(map, MovementMode.GROUND, next, (source, candidate) ->
                (source.equals(a) && candidate.equals(c))
                        || (source.equals(b) && candidate.equals(d)));

        // B is processed first because Y descends. Its in-place update exposes
        // D to A, and A->D fails, so A remains B instead of advancing to C.
        equal(b, map.coordinate(next[map.index(a)]), "later cell observes earlier mutated target chain");
        equal(d, map.coordinate(next[map.index(b)]), "earlier cell advances to D");
    }

    private static void twoCheckpointInertiaTurn() {
        GridMap map = new GridMap(5, 6);
        Route route = new Route(
                new Vec2f(0.5f, 0.5f),
                new Vec2f(1.5f, 3.5f),
                List.of(Checkpoint.move(new Vec2f(1.5f, 0.5f)), Checkpoint.move(new Vec2f(1.5f, 2.5f))),
                MovementMode.GROUND,
                true,
                true,
                false);
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));

        for (int i = 0; i < 60; i++) {
            simulator.tick();
        }
        equal(1, simulator.unit().routeProgress().checkpointIndex(), "first checkpoint should complete after movement");
        equal(1.4541667f, simulator.unit().cursorPosition().x(), 0.00001f, "first checkpoint radius position");
        equal(0.5f, simulator.unit().inertiaVelocity().x(), 0.00001f, "rightward inertia remains at transition");

        FrameTrace firstTurnFrame = simulator.tick();
        equal(0.3697214f, firstTurnFrame.inertiaAfter().x(), 0.00002f, "right velocity turns by steering force");
        equal(0.1332984f, firstTurnFrame.inertiaAfter().y(), 0.00002f, "downward velocity starts on next frame");
        truth(firstTurnFrame.entityAfter().x() > 1.4541667f, "the first turn frame still moves right");

        while (simulator.unit().routeProgress().checkpointIndex() < 2 && simulator.frame() < 300) {
            simulator.tick();
        }
        equal(2, simulator.unit().routeProgress().checkpointIndex(), "second checkpoint should complete");
        equal(1.5000353f, simulator.unit().cursorPosition().x(), 0.00005f, "turn settles near target column");
        equal(2.4541564f, simulator.unit().cursorPosition().y(), 0.00005f, "second checkpoint completes at radius, not center");
    }

    private static void avoidanceRefreshCadence() {
        GridMap map = new GridMap(4, 4);
        map.setRule(new TileCoord(2, 1), TileRule.costlyObstacle(TileRule.BOX_COST));
        Route route = new Route(
                new Vec2f(1.8f, 1.5f), new Vec2f(3.5f, 1.5f),
                List.of(Checkpoint.waitForSeconds(10f)), MovementMode.GROUND, true, true, false);
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));

        FrameTrace zero = simulator.tick();
        FrameTrace one = simulator.tick();
        FrameTrace two = simulator.tick();
        FrameTrace three = simulator.tick();
        truth(zero.avoidanceRecomputed(), "frame zero recalculates");
        falsity(one.avoidanceRecomputed(), "frame one reuses");
        falsity(two.avoidanceRecomputed(), "frame two reuses");
        truth(three.avoidanceRecomputed(), "frame three recalculates");
        truth(zero.avoidance().x() < 0f, "near right obstacle produces left avoidance");
    }

    private static void portalPreservesInertia() {
        GridMap map = new GridMap(6, 3);
        Route route = new Route(
                new Vec2f(0.5f, 0.5f), new Vec2f(5.5f, 0.5f),
                List.of(
                        Checkpoint.move(new Vec2f(1.5f, 0.5f)),
                        Checkpoint.disappear(),
                        Checkpoint.waitForSeconds(0.1f),
                        Checkpoint.appearAt(new Vec2f(3.5f, 0.5f)),
                        Checkpoint.move(new Vec2f(4.5f, 0.5f))),
                MovementMode.GROUND, true, true, false);
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        for (int i = 0; i < 60; i++) {
            simulator.tick();
        }
        equal(UnitMode.VANISHED, simulator.unit().mode(), "disappear checkpoint enters vanished state");
        Vec2f beforePortal = simulator.unit().inertiaVelocity();

        FrameTrace portalFrame = null;
        for (int i = 0; i < 10; i++) {
            FrameTrace candidate = simulator.tick();
            if (candidate.transition().contains("complete APPEAR_AT_POS")) {
                portalFrame = candidate;
                break;
            }
        }
        truth(portalFrame != null, "appearance checkpoint should execute");
        equal(new Vec2f(3.5f, 0.5f), portalFrame.cursorAfter(), "appearance relocates cursor");
        equal(beforePortal.x(), simulator.unit().inertiaVelocity().x(), 0f, "portal retains inertia x exactly");
        equal(beforePortal.y(), simulator.unit().inertiaVelocity().y(), 0f, "portal retains inertia y exactly");
        equal(UnitMode.MOVE, simulator.unit().mode(), "appearance restores move state");
    }

    private static void collisionReflection() {
        GridMap map = new GridMap(3, 1);
        map.setRule(new TileCoord(1, 0), TileRule.impassable());
        Route route = new Route(new Vec2f(0.9f, 0.5f), new Vec2f(2.5f, 0.5f),
                List.of(), MovementMode.GROUND, true, true, false);
        UnitState unit = new UnitState(route, UnitConfig.normalGround(1f));
        Vec2f corrected = new CollisionResolver().resolve(map, MovementMode.GROUND, unit, new Vec2f(0.2f, 0f));
        equal(-0.2f, corrected.x(), 0.00001f, "head-on entry reflects x displacement");
        equal(0f, corrected.y(), 0.00001f, "head-on entry keeps y displacement");
    }

    private static void ignoredCheckpoints() {
        GridMap map = new GridMap(4, 2);
        Route route = new Route(
                new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f),
                List.of(Checkpoint.alert(), Checkpoint.waitForSeconds(100f), Checkpoint.move(new Vec2f(1.5f, 0.5f))),
                MovementMode.GROUND, true, true, true);
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        simulator.tick();
        equal(0, simulator.unit().alertsShown(), "ignored alert must not fire");
        equal(2, simulator.unit().routeProgress().checkpointIndex(), "ignored non-movement checkpoints are skipped");
    }

    private static void routeDistanceAcrossPortal() {
        GridMap map = new GridMap(7, 1);
        Route route = new Route(
                new Vec2f(0.5f, 0.5f), new Vec2f(5.5f, 0.5f),
                List.of(
                        Checkpoint.move(new Vec2f(1.5f, 0.5f)),
                        Checkpoint.disappear(),
                        Checkpoint.appearAt(new Vec2f(4.5f, 0.5f))),
                MovementMode.GROUND, true, true, false);
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        PathMap firstMove = simulator.pathMapForCheckpoint(0);
        equal(2f, firstMove.distanceToEnd(new TileCoord(0, 0)), 0.00001f,
                "distance includes travel to first checkpoint and from portal exit, not portal span");
        equal(5f, simulator.endpointPathMap().distanceToEnd(new TileCoord(0, 0)), 0.00001f,
                "endpoint map retains ordinary direct grid distance");
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            throw new AssertionError("FAIL " + name, error);
        }
    }

    private static void truth(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void falsity(boolean condition, String message) {
        truth(!condition, message);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
        }
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
        }
    }

    private static void equal(float expected, float actual, float tolerance, String message) {
        if (Float.isNaN(actual) || Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
        }
    }
}
