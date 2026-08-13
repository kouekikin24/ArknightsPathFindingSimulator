import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dependency-free executable regression suite for the confirmed simulator rules. */
public final class SimulatorTests {
    private static int passed;

    public static void main(String[] args) {
        run("tile ownership uses floor for lines and negative positions", SimulatorTests::tileOwnershipUsesFloor);
        run("Unity-style vector normalization and clamp", SimulatorTests::unityVectorOperations);
        run("avoidance normalizes then removes given-direction projection", SimulatorTests::avoidanceNormalizationAndProjection);
        run("reverse SPFA charges the entered tile", SimulatorTests::nonUniformSpfaCosts);
        run("modified Bresenham blocks corner cutting", SimulatorTests::modifiedBresenhamCorner);
        run("modified Bresenham checks confirmed narrow bands", SimulatorTests::modifiedBresenhamNarrowBand);
        run("nextNode smoothing is ordered and in place", SimulatorTests::inPlaceNextNodeSmoothing);
        run("orthogonal smoothing never creates a diagonal next node", SimulatorTests::orthogonalSmoothing);
        run("external global frames control avoidance cadence", SimulatorTests::globalFrameCadence);
        run("bound and outside-map MOVE frames still refresh avoidance", SimulatorTests::boundAndOutsideAvoidanceCadence);
        run("movement uses continuous integration without position snapping", SimulatorTests::movementDoesNotSnap);
        run("outside-map spawn preserves movement checkpoints", SimulatorTests::outsideMapSpawnPreservesCheckpoints);
        run("unreachable movement checkpoint enters blocked once", SimulatorTests::unreachableMoveBlocks);
        run("unreachable endpoint enters blocked once", SimulatorTests::unreachableEndpointBlocks);
        run("swept collision slides instead of reflecting", SimulatorTests::sweptCollisionSlide);
        run("swept collision handles corners, long sweeps, and bounds", SimulatorTests::sweptCollisionCornerLongSweepAndBounds);
        run("swept collision orders near-simultaneous crossings strictly", SimulatorTests::sweptCollisionUsesStrictCrossingOrder);
        run("path maps stay immutable and cache evicts only stale map versions", SimulatorTests::pathMapImmutabilityAndCacheEviction);
        run("route-owned distances separate same tile goals and refresh on map change", SimulatorTests::routeOwnedDistances);
        run("invalid disappear paths are rejected", SimulatorTests::invalidDisappearPaths);
        run("cursor history records the previous distinct tile", SimulatorTests::cursorHistoryUsesDistinctTiles);
        run("same-tile route target switch resets node-visit history", SimulatorTests::sameTileTargetSwitchResetsVisitHistory);
        run("trace ring buffer retains order and listener receives all frames", SimulatorTests::traceRingBufferAndListener);
        run("flying mode ignores all terrain obstacles", SimulatorTests::flyingMode);
        run("confirmed patrol loop rule and loop distance", SimulatorTests::patrolLoopRule);
        run("patrol loop stops checkpoint scanning for the current frame", SimulatorTests::patrolLoopStopsCheckpointScan);
        run("wait checkpoints hold inertia while still refreshing avoidance", SimulatorTests::waitAndAlertBehavior);
        run("all stage-clock wait checkpoint kinds advance", SimulatorTests::stageClockWaitCheckpointKinds);
        run("ignored checkpoints skip side effects and preserve portals", SimulatorTests::ignoredCheckpoints);
        run("portal relocation preserves inertia velocity", SimulatorTests::portalPreservesInertia);
        run("early endpoint completion hides remaining checkpoints", SimulatorTests::earlyEndpointCompletionHidesCurrentCheckpoint);
        run("parameter validation rejects invalid input", SimulatorTests::parameterValidation);
        System.out.println("Passed " + passed + " simulator tests.");
    }

    private static void tileOwnershipUsesFloor() {
        equal(new TileCoord(0, 0), TileCoord.fromPosition(new Vec2f(0.5f, 0.5f)), "first center");
        equal(new TileCoord(0, 0), TileCoord.fromPosition(new Vec2f(0.99999994f, 0.5f)), "left-side float drift");
        equal(new TileCoord(1, 0), TileCoord.fromPosition(new Vec2f(1.0f, 0.5f)), "integer line belongs to positive X tile");
        equal(new TileCoord(3, 2), TileCoord.fromPosition(new Vec2f(3.0f, 2.9999998f)), "integer X line has no parity bias");
        equal(new TileCoord(-1, -1), TileCoord.fromPosition(new Vec2f(-0.0000001f, -0.0000001f)),
                "negative values use mathematical floor");
        equal(new TileCoord(-1, -2), TileCoord.fromPosition(new Vec2f(-1.0f, -1.0000001f)),
                "negative integer line and drift remain unambiguous");
    }

    private static void unityVectorOperations() {
        Vec2f normalized = new Vec2f(3f, 4f).normalized();
        equal(0.6f, normalized.x(), 0.000001f, "normalization divides X by magnitude");
        equal(0.8f, normalized.y(), 0.000001f, "normalization divides Y by magnitude");
        equal(Vec2f.ZERO, new Vec2f(0.00001f, 0f).normalized(), "Unity near-zero threshold is strict");

        Vec2f clamped = new Vec2f(3f, 4f).clampMagnitude(3f);
        equal(1.8f, clamped.x(), 0.000001f, "clamp normalizes before applying magnitude");
        equal(2.4f, clamped.y(), 0.000001f, "clamp preserves direction");
        equal(new Vec2f(3f, 4f), new Vec2f(3f, 4f).clampMagnitude(5f),
                "vector at limit is not altered");
    }

    private static void avoidanceNormalizationAndProjection() {
        GridMap map = new GridMap(4, 3);
        map.setRule(new TileCoord(2, 1), TileRule.costlyObstacle(TileRule.BOX_COST));
        Route route = route(new Vec2f(1.8f, 1.5f), new Vec2f(3.5f, 1.5f), List.of());
        UnitConfig config = UnitConfig.normalGround(1f);
        UnitState unit = new UnitState(route, config);
        AvoidanceCalculator calculator = new AvoidanceCalculator();

        Vec2f lateral = calculator.calculate(map, MovementMode.GROUND, config, unit, new Vec2f(0f, 1f));
        equal(-1f, lateral.x(), 0.000001f, "8-neighbor sum is normalized before projection");
        equal(0f, lateral.y(), 0.000001f, "right obstacle has no vertical component");
        equal(1f, lateral.length(), 0.000001f, "normalized lateral avoidance has unit length");

        Vec2f projectedAway = calculator.calculate(map, MovementMode.GROUND, config, unit, new Vec2f(1f, 0f));
        equal(Vec2f.ZERO, projectedAway, "given-direction projection removes parallel avoidance");

        map.setRule(new TileCoord(1, 1), TileRule.impassable());
        Vec2f towardPassable = calculator.calculate(map, MovementMode.GROUND, config, unit, Vec2f.ZERO);
        equal(new Vec2f(0f, -1f), towardPassable,
                "inside an impassable tile, nearest-passable direction is normalized");
    }

    private static void nonUniformSpfaCosts() {
        GridMap map = new GridMap(5, 1);
        map.setRule(new TileCoord(2, 0), TileRule.costlyObstacle(TileRule.BOX_COST));
        PathMap path = new PathMapBuilder().build(map, new TileCoord(4, 0), MovementMode.GROUND, true);

        equal(2f, path.distanceToTarget(new TileCoord(2, 0)), 0.000001f,
                "standing in a costly tile does not charge that tile again");
        equal(1_002f, path.distanceToTarget(new TileCoord(1, 0)), 0.000001f,
                "route charges entry into the costly tile");
        equal(1_003f, path.distanceToTarget(new TileCoord(0, 0)), 0.000001f,
                "all entered tiles contribute in forward order");
        equal(new TileCoord(3, 0), path.rawNextNode(new TileCoord(2, 0)),
                "next-node direction remains toward the target");
    }

    private static void modifiedBresenhamCorner() {
        GridMap map = new GridMap(3, 3);
        truth(ModifiedBresenham.canLink(map, MovementMode.GROUND, new TileCoord(0, 0), new TileCoord(2, 2)),
                "open diagonal should link");
        map.setRule(new TileCoord(1, 0), TileRule.costlyObstacle(TileRule.BOX_COST));
        falsity(ModifiedBresenham.canLink(map, MovementMode.GROUND, new TileCoord(0, 0), new TileCoord(2, 2)),
                "extra corner tile must block diagonal corner cutting");
    }

    private static void modifiedBresenhamNarrowBand() {
        GridMap map = new GridMap(2, 4);
        truth(ModifiedBresenham.canLink(map, MovementMode.GROUND, new TileCoord(0, 0), new TileCoord(1, 3)),
                "open narrow band should link");
        map.setRule(new TileCoord(1, 0), TileRule.costlyObstacle(TileRule.BOX_COST));
        falsity(ModifiedBresenham.canLink(map, MovementMode.GROUND, new TileCoord(0, 0), new TileCoord(1, 3)),
                "confirmed 2xN narrow-band rule checks the entire band");
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

        equal(b, map.coordinate(next[map.index(a)]), "later tile observes earlier in-place mutation");
        equal(d, map.coordinate(next[map.index(b)]), "earlier tile advances to D");
    }

    private static void orthogonalSmoothing() {
        GridMap map = new GridMap(4, 3);
        PathMap path = new PathMapBuilder().build(map, new TileCoord(3, 2), MovementMode.GROUND, false);
        TileCoord source = new TileCoord(0, 0);
        TileCoord next = path.nextNode(source);
        truth(next != null, "reachable source has a next node");
        truth(next.x() == source.x() || next.y() == source.y(),
                "orthogonal smoothing never skips to a diagonal node");
    }

    private static void globalFrameCadence() {
        GridMap map = new GridMap(8, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(7.5f, 0.5f), List.of());
        PathfindingSimulator first = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        PathfindingSimulator second = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));

        FrameTrace firstFive = first.tick(5L);
        FrameTrace firstSix = first.tick(6L);
        FrameTrace firstSeven = first.tick(7L);
        FrameTrace firstEight = first.tick(8L);
        FrameTrace firstNine = first.tick(9L);
        FrameTrace secondSix = second.tick(6L);
        FrameTrace secondSeven = second.tick(7L);

        falsity(firstFive.avoidanceRecomputed(), "first allowed frame may use any non-negative global phase");
        truth(firstSix.avoidanceRecomputed(), "global frame 6 recomputes avoidance");
        falsity(firstSeven.avoidanceRecomputed(), "global frame 7 reuses avoidance");
        falsity(firstEight.avoidanceRecomputed(), "global frame 8 reuses avoidance");
        truth(firstNine.avoidanceRecomputed(), "global frame 9 recomputes avoidance");
        truth(secondSix.avoidanceRecomputed(), "independent simulators share the caller's global phase");
        falsity(secondSeven.avoidanceRecomputed(), "local frame number does not control cadence");
        equal(0, firstFive.frame(), "first local frame remains an audit counter");
        equal(5L, firstFive.globalFrame(), "trace records supplied global frame");
        equal(9L, first.lastGlobalFrame(), "simulator tracks last external frame");

        expectIllegalArgument(() -> first.tick(11L), "Global frames must be consecutive");
        PathfindingSimulator fresh = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        expectIllegalArgument(() -> fresh.tick(-1L), "Global frame must be non-negative");
    }

    private static void boundAndOutsideAvoidanceCadence() {
        GridMap map = new GridMap(4, 1);
        Route inside = route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of());
        PathfindingSimulator bound = new PathfindingSimulator(map, inside, UnitConfig.normalGround(1f));
        bound.unit().setBound(true);
        FrameTrace boundZero = bound.tick(0L);
        FrameTrace boundOne = bound.tick(1L);
        FrameTrace boundThree = bound.tick(2L);
        FrameTrace boundThreeAgain = bound.tick(3L);
        truth(boundZero.avoidanceRecomputed(), "bound MOVE frame zero refreshes avoidance");
        falsity(boundOne.avoidanceRecomputed(), "bound non-phase frame reuses avoidance");
        falsity(boundThree.avoidanceRecomputed(), "bound frame two reuses avoidance");
        truth(boundThreeAgain.avoidanceRecomputed(), "bound frame three refreshes avoidance");
        equal(3L, bound.unit().lastAvoidanceFrame(), "cached frame follows global frame while bound");

        Route outside = route(new Vec2f(-0.1f, 0.5f), new Vec2f(3.5f, 0.5f), List.of());
        PathfindingSimulator outsideSimulator = new PathfindingSimulator(map, outside, UnitConfig.normalGround(1f));
        FrameTrace outsideZero = outsideSimulator.tick(0L);
        truth(outsideZero.avoidanceRecomputed(), "outside-map MOVE phase still refreshes avoidance");
        equal(0L, outsideSimulator.unit().lastAvoidanceFrame(), "outside-map cache records phase frame");
    }

    private static void movementDoesNotSnap() {
        GridMap map = new GridMap(6, 4);
        map.setRule(new TileCoord(2, 1), TileRule.impassable());
        map.setRule(new TileCoord(2, 2), TileRule.impassable());
        Route route = route(new Vec2f(0.5f, 1.5f), new Vec2f(5.5f, 1.5f), List.of());
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));

        boolean observedIntermediateNode = false;
        for (long globalFrame = 0L; globalFrame < 600L && simulator.unit().mode() == UnitMode.MOVE; globalFrame++) {
            FrameTrace trace = simulator.tick(globalFrame);
            if (trace.target() != null && !trace.target().equals(route.endpoint())) {
                observedIntermediateNode = true;
                equal(trace.requestedDisplacement(), trace.appliedDisplacement(),
                        "unblocked intermediate-node motion must use its integrated displacement");
            }
        }
        truth(observedIntermediateNode, "obstacle route exposes an intermediate next node");

        Route checkpointRoute = route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(1.5f, 0.5f))));
        PathfindingSimulator checkpointSimulator = new PathfindingSimulator(
                new GridMap(4, 1), checkpointRoute, UnitConfig.normalGround(1f));
        for (long globalFrame = 0L; globalFrame < 300L
                && checkpointSimulator.unit().routeProgress().checkpointIndex() == 0; globalFrame++) {
            checkpointSimulator.tick(globalFrame);
        }
        Vec2f checkpointPosition = checkpointSimulator.unit().cursorPosition();
        equal(1, checkpointSimulator.unit().routeProgress().checkpointIndex(),
                "checkpoint completes by its radius");
        truth(checkpointPosition.distanceTo(new Vec2f(1.5f, 0.5f)) <= 0.05f,
                "checkpoint completion position is inside its radius");
        truth(checkpointPosition.distanceTo(new Vec2f(1.5f, 0.5f)) > 0.00001f,
                "checkpoint completion does not snap position to its center");

        Route endpointRoute = route(new Vec2f(0.5f, 0.5f), new Vec2f(1.5f, 0.5f), List.of());
        PathfindingSimulator endpointSimulator = new PathfindingSimulator(
                new GridMap(3, 1), endpointRoute, UnitConfig.normalGround(1f));
        for (long globalFrame = 0L; globalFrame < 300L && !endpointSimulator.unit().routeProgress().completed(); globalFrame++) {
            endpointSimulator.tick(globalFrame);
        }
        Vec2f endpointPosition = endpointSimulator.unit().cursorPosition();
        truth(endpointSimulator.unit().routeProgress().completed(), "endpoint completes by its radius");
        truth(endpointPosition.distanceTo(endpointRoute.endpoint()) <= 0.05f,
                "endpoint completion position is inside its radius");
        truth(endpointPosition.distanceTo(endpointRoute.endpoint()) > 0.00001f,
                "endpoint completion does not snap position to its center");
    }

    private static void outsideMapSpawnPreservesCheckpoints() {
        GridMap map = new GridMap(4, 1);
        Route route = route(new Vec2f(-0.2f, 0.5f), new Vec2f(3.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(0.5f, 0.5f)),
                Checkpoint.move(new Vec2f(2.5f, 0.5f))));
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));

        for (long globalFrame = 0L; globalFrame < 6L; globalFrame++) {
            FrameTrace trace = simulator.tick(globalFrame);
            equal(0, simulator.unit().routeProgress().checkpointIndex(),
                    "outside-map entry must not consume MOVE checkpoints");
            falsity(trace.transition().contains("complete MOVE"), "outside-map frame must not complete a MOVE checkpoint");
        }
    }

    private static void unreachableMoveBlocks() {
        GridMap map = new GridMap(3, 1);
        map.setRule(new TileCoord(1, 0), TileRule.impassable());
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(2.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(2.5f, 0.5f))));
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));

        FrameTrace blocked = simulator.tick(0L);
        equal(UnitMode.BLOCKED, simulator.unit().mode(), "unreachable MOVE enters blocked state");
        equal(0, simulator.unit().routeProgress().checkpointIndex(), "blocked MOVE stays current");
        equal("blocked unreachable MOVE", blocked.transition(), "blocked diagnostic is explicit");
        FrameTrace held = simulator.tick(1L);
        equal("", held.transition(), "blocked diagnostic is emitted once");
        falsity(held.avoidanceRecomputed(), "blocked state no longer refreshes avoidance");
    }

    private static void unreachableEndpointBlocks() {
        GridMap map = new GridMap(3, 1);
        map.setRule(new TileCoord(1, 0), TileRule.impassable());
        PathfindingSimulator simulator = new PathfindingSimulator(map,
                route(new Vec2f(0.5f, 0.5f), new Vec2f(2.5f, 0.5f), List.of()), UnitConfig.normalGround(1f));

        FrameTrace blocked = simulator.tick(0L);
        equal(UnitMode.BLOCKED, simulator.unit().mode(), "unreachable endpoint enters blocked state");
        equal("blocked unreachable ENDPOINT", blocked.transition(), "endpoint diagnostic is explicit");
        FrameTrace held = simulator.tick(1L);
        equal("", held.transition(), "endpoint diagnostic is emitted once");
        falsity(held.avoidanceRecomputed(), "blocked endpoint no longer refreshes avoidance");
    }

    private static void sweptCollisionSlide() {
        GridMap map = new GridMap(3, 2);
        map.setRule(new TileCoord(1, 0), TileRule.impassable());
        UnitState unit = new UnitState(route(new Vec2f(0.9f, 0.5f), new Vec2f(2.5f, 0.5f), List.of()),
                UnitConfig.normalGround(1f));
        CollisionResolver resolver = new CollisionResolver();

        CollisionResult headOn = resolver.resolve(map, MovementMode.GROUND, unit,
                new Vec2f(0.2f, 0f), new Vec2f(0.2f, 0f));
        truth(headOn.collided(), "wall entry collides");
        equal(Vec2f.ZERO, headOn.appliedDisplacement(), "head-on collision removes full normal displacement");
        equal(Vec2f.ZERO, headOn.inertiaVelocity(), "head-on collision removes normal inertia");

        CollisionResult slide = resolver.resolve(map, MovementMode.GROUND, unit,
                new Vec2f(0.2f, 0.4f), new Vec2f(0.2f, 0.4f));
        truth(slide.collided(), "diagonal wall entry collides");
        equal(new Vec2f(0f, 0.4f), slide.appliedDisplacement(), "collision retains full tangential displacement");
        equal(new Vec2f(0f, 0.4f), slide.inertiaVelocity(), "collision retains tangential inertia");
    }

    private static void sweptCollisionCornerLongSweepAndBounds() {
        CollisionResolver resolver = new CollisionResolver();

        GridMap cornerMap = new GridMap(3, 3);
        cornerMap.setRule(new TileCoord(1, 1), TileRule.impassable());
        UnitState cornerUnit = new UnitState(route(new Vec2f(0.9f, 0.9f), new Vec2f(2.5f, 2.5f), List.of()),
                UnitConfig.normalGround(1f));
        CollisionResult corner = resolver.resolve(cornerMap, MovementMode.GROUND, cornerUnit,
                new Vec2f(0.2f, 0.2f), new Vec2f(0.2f, 0.2f));
        equal(Vec2f.ZERO, corner.appliedDisplacement(), "blocked diagonal at simultaneous crossing removes both axes");
        equal(Vec2f.ZERO, corner.inertiaVelocity(), "blocked diagonal removes both velocity axes");

        GridMap longSweepMap = new GridMap(5, 1);
        longSweepMap.setRule(new TileCoord(3, 0), TileRule.impassable());
        UnitState longSweepUnit = new UnitState(route(new Vec2f(0.5f, 0.5f), new Vec2f(4.5f, 0.5f), List.of()),
                UnitConfig.normalGround(1f));
        CollisionResult longSweep = resolver.resolve(longSweepMap, MovementMode.GROUND, longSweepUnit,
                new Vec2f(3.5f, 0f), new Vec2f(3.5f, 0f));
        equal(Vec2f.ZERO, longSweep.appliedDisplacement(), "DDA detects a wall after traversing open tiles");

        GridMap boundsMap = new GridMap(1, 1);
        UnitState boundsUnit = new UnitState(route(new Vec2f(0.5f, 0.5f), new Vec2f(0.5f, 0.5f), List.of()),
                UnitConfig.normalGround(1f));
        CollisionResult bounds = resolver.resolve(boundsMap, MovementMode.GROUND, boundsUnit,
                new Vec2f(0.6f, 0f), new Vec2f(0.6f, 0f));
        equal(Vec2f.ZERO, bounds.appliedDisplacement(), "map bounds are collision-blocked");
    }

    private static void sweptCollisionUsesStrictCrossingOrder() {
        GridMap map = new GridMap(2, 2);
        map.setRule(new TileCoord(1, 1), TileRule.impassable());
        UnitState unit = new UnitState(route(new Vec2f(0.5f, 0.5f), new Vec2f(1.5f, 0.5f), List.of()),
                UnitConfig.normalGround(1f));
        CollisionResult result = new CollisionResolver().resolve(map, MovementMode.GROUND, unit,
                new Vec2f(1f, 0.9999995f), new Vec2f(1f, 0.9999995f));

        equal(new Vec2f(1f, 0f), result.appliedDisplacement(),
                "a later Y crossing clears only Y after X enters the open neighbor");
        equal(new Vec2f(1f, 0f), result.inertiaVelocity(),
                "strict crossing order keeps the earlier X inertia component");
    }

    private static void pathMapImmutabilityAndCacheEviction() {
        GridMap mapA = new GridMap(3, 1);
        PathMap immutable = new PathMapBuilder().build(mapA, new TileCoord(2, 0), MovementMode.GROUND, true);
        TileCoord before = immutable.nextNode(new TileCoord(0, 0));
        int[] leakedCopy = immutable.copyNextIndices();
        leakedCopy[mapA.index(new TileCoord(0, 0))] = -1;
        equal(before, immutable.nextNode(new TileCoord(0, 0)), "mutating a copied next-node array cannot alter PathMap");

        GridMap mapB = new GridMap(3, 1);
        PathMapCache cache = new PathMapCache();
        PathMap original = cache.get(mapA, new TileCoord(2, 0), MovementMode.GROUND, true);
        same(original, cache.get(mapA, new TileCoord(2, 0), MovementMode.GROUND, true), "same cache key reuses PathMap");
        cache.get(mapA, new TileCoord(1, 0), MovementMode.GROUND, true);
        cache.get(mapB, new TileCoord(2, 0), MovementMode.GROUND, true);
        equal(3, cache.entryCount(), "cache holds two targets for A and one for B");
        mapA.setRule(new TileCoord(1, 0), TileRule.costlyObstacle(TileRule.BOX_COST));
        PathMap refreshed = cache.get(mapA, new TileCoord(2, 0), MovementMode.GROUND, true);
        notSame(original, refreshed, "map version mutation rebuilds affected PathMap");
        equal(2, cache.entryCount(), "stale A entries are evicted while B remains cached");
    }

    private static void routeOwnedDistances() {
        GridMap sameTileMap = new GridMap(5, 1);
        Route sameTileRoute = route(new Vec2f(0.5f, 0.5f), new Vec2f(2.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(2.5f, 0.5f)),
                Checkpoint.disappear(),
                Checkpoint.appearAt(new Vec2f(4.5f, 0.5f)),
                Checkpoint.move(new Vec2f(2.5f, 0.5f))));
        PathfindingSimulator sameTile = new PathfindingSimulator(sameTileMap, sameTileRoute, UnitConfig.normalGround(1f));
        TileCoord start = new TileCoord(0, 0);
        equal(4f, sameTile.remainingRouteDistanceForCheckpoint(0, start), 0.000001f,
                "first same-tile goal includes portal continuation from its own route index");
        equal(2f, sameTile.remainingRouteDistanceForCheckpoint(3, start), 0.000001f,
                "later same-tile goal has an independent remaining-distance array");
        same(sameTile.pathMapForCheckpoint(0), sameTile.pathMapForCheckpoint(3),
                "navigation cache may share a same-tile PathMap without sharing route distance");

        GridMap mutableMap = new GridMap(4, 1);
        PathfindingSimulator mutable = new PathfindingSimulator(mutableMap,
                route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of()), UnitConfig.normalGround(1f));
        TileCoord mutableStart = new TileCoord(0, 0);
        equal(3f, mutable.remainingRouteDistanceToEndpoint(mutableStart), 0.000001f,
                "open-map route distance is unit entry cost per step");
        mutableMap.setRule(new TileCoord(1, 0), TileRule.costlyObstacle(TileRule.BOX_COST));
        equal(1_002f, mutable.remainingRouteDistanceToEndpoint(mutableStart), 0.000001f,
                "map version change rebuilds route distance arrays");
    }

    private static void invalidDisappearPaths() {
        expectIllegalArgument(() -> route(new Vec2f(0.5f, 0.5f), new Vec2f(1.5f, 0.5f),
                List.of(Checkpoint.disappear())), "checkpoint 0");
        expectIllegalArgument(() -> route(new Vec2f(0.5f, 0.5f), new Vec2f(1.5f, 0.5f),
                List.of(Checkpoint.disappear(), Checkpoint.waitForSeconds(1f), Checkpoint.move(new Vec2f(1.5f, 0.5f)))),
                "checkpoint 0");
        Route valid = route(new Vec2f(0.5f, 0.5f), new Vec2f(1.5f, 0.5f),
                List.of(Checkpoint.disappear(), Checkpoint.waitForSeconds(1f), Checkpoint.appearAt(new Vec2f(0.5f, 0.5f))));
        equal(3, valid.checkpoints().size(), "appearance before route end makes disappear path valid");
    }

    private static void cursorHistoryUsesDistinctTiles() {
        UnitState unit = new UnitState(route(new Vec2f(0.5f, 0.5f), new Vec2f(2.5f, 0.5f), List.of()),
                UnitConfig.normalGround(1f));
        unit.resetVisitState(new TileCoord(2, 0), 0);
        unit.observeCursorTile(new TileCoord(0, 0));
        unit.observeCursorTile(new TileCoord(0, 0));
        equal(null, unit.previousDistinctCursorTile(), "same-tile observations do not manufacture a predecessor");
        unit.observeCursorTile(new TileCoord(1, 0));
        equal(new TileCoord(0, 0), unit.previousDistinctCursorTile(), "first tile change records prior distinct tile");
        unit.observeCursorTile(new TileCoord(1, 0));
        equal(new TileCoord(0, 0), unit.previousDistinctCursorTile(), "persistent node visit state survives later frames in same tile");
        unit.observeCursorTile(new TileCoord(2, 0));
        equal(new TileCoord(1, 0), unit.previousDistinctCursorTile(), "next distinct transition advances predecessor");
        unit.resetVisitState(new TileCoord(3, 0), 1);
        equal(null, unit.previousDistinctCursorTile(), "target switch resets distinct-tile history");
    }

    private static void sameTileTargetSwitchResetsVisitHistory() {
        UnitState unit = new UnitState(route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of()),
                UnitConfig.normalGround(1f));
        TileCoord sharedTargetTile = new TileCoord(1, 0);
        unit.resetVisitState(sharedTargetTile, 0);
        unit.observeCursorTile(new TileCoord(0, 0));
        unit.observeCursorTile(sharedTargetTile);
        equal(new TileCoord(0, 0), unit.previousDistinctCursorTile(),
                "first target records cursor history");
        falsity(unit.visitStateMatches(sharedTargetTile, 1),
                "same map tile is not enough to identify a new route target");
        unit.resetVisitState(sharedTargetTile, 1);
        equal(null, unit.previousDistinctCursorTile(),
                "same-tile next checkpoint clears the prior target's history");
        truth(unit.visitStateMatches(sharedTargetTile, 1),
                "visit-state identity includes route target index");
    }

    private static void traceRingBufferAndListener() {
        GridMap map = new GridMap(8, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(7.5f, 0.5f), List.of());
        List<FrameTrace> heard = new ArrayList<>();
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f), 2, heard::add);
        simulator.tick(10L);
        simulator.tick(11L);
        simulator.tick(12L);

        List<FrameTrace> retained = simulator.trace();
        equal(2, retained.size(), "ring trace limits retained frames");
        equal(1, retained.get(0).frame(), "trace keeps chronological local-frame order after eviction");
        equal(11L, retained.get(0).globalFrame(), "trace keeps chronological global-frame order after eviction");
        equal(12L, retained.get(1).globalFrame(), "last retained global frame is newest");
        equal(1L, simulator.droppedTraceFrames(), "ring trace counts discarded frames");
        equal(3, heard.size(), "synchronous listener receives complete trace before ring eviction");
        equal(10L, heard.get(0).globalFrame(), "listener sees first frame too");
    }

    private static void flyingMode() {
        GridMap map = new GridMap(5, 1);
        map.setRule(new TileCoord(1, 0), TileRule.costlyObstacle(TileRule.BOX_COST));
        map.setRule(new TileCoord(2, 0), TileRule.costlyObstacle(TileRule.PIT_COST));
        map.setRule(new TileCoord(3, 0), TileRule.impassable());
        List<Checkpoint> checkpoints = List.of(Checkpoint.move(new Vec2f(4.5f, 0.5f)));
        PathfindingSimulator ground = new PathfindingSimulator(map,
                new Route(new Vec2f(0.5f, 0.5f), new Vec2f(4.5f, 0.5f), checkpoints,
                        MovementMode.GROUND, true, true, false), UnitConfig.normalGround(1f));
        PathfindingSimulator flying = new PathfindingSimulator(map,
                new Route(new Vec2f(0.5f, 0.5f), new Vec2f(4.5f, 0.5f), checkpoints,
                        MovementMode.FLYING, true, true, false), UnitConfig.normalFlying(1f));

        ground.tick(0L);
        FrameTrace flyingTrace = flying.tick(0L);
        equal(UnitMode.BLOCKED, ground.unit().mode(), "ground unit cannot cross the wall");
        equal(UnitMode.MOVE, flying.unit().mode(), "flying unit remains movable through all terrain");
        equal(Vec2f.ZERO, flyingTrace.avoidance(), "flying terrain creates no avoidance force");
        truth(flyingTrace.nextNode().equals(new TileCoord(4, 0)),
                "flying path smoothing links directly through every terrain type");

        CollisionResult collision = new CollisionResolver().resolve(map, MovementMode.FLYING,
                flying.unit(), new Vec2f(4f, 0f), new Vec2f(4f, 0f));
        falsity(collision.collided(), "flying movement does not collide with terrain");
        equal(new Vec2f(4f, 0f), collision.appliedDisplacement(),
                "flying collision sweep preserves terrain-crossing displacement");
        equal(new Vec2f(4f, 0f), collision.inertiaVelocity(),
                "flying collision sweep preserves inertia velocity");
    }

    private static void patrolLoopRule() {
        Route looping = route(new Vec2f(0.5f, 0.5f), new Vec2f(4.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(1.5f, 0.5f)),
                Checkpoint.patrolMove(new Vec2f(3.5f, 0.5f))));
        RouteProgress progress = new RouteProgress();
        StageClock clock = new StageClock();
        falsity(progress.advance(looping, clock), "ordinary checkpoint advancement does not report a loop");
        equal(1, progress.checkpointIndex(), "first checkpoint advances normally");
        truth(progress.advance(looping, clock), "terminal patrol reports its return to checkpoint zero");
        equal(0, progress.checkpointIndex(), "terminal patrol with different target returns to first checkpoint");
        PathfindingSimulator loopingSimulator = new PathfindingSimulator(new GridMap(5, 1), looping, UnitConfig.normalGround(1f));
        truth(Float.isInfinite(loopingSimulator.remainingRouteDistanceForCheckpoint(0, new TileCoord(0, 0))),
                "looping patrol route has infinite remaining route distance");

        Route terminal = route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(1.5f, 0.5f)),
                Checkpoint.patrolMove(new Vec2f(1.5f, 0.5f))));
        RouteProgress terminalProgress = new RouteProgress();
        terminalProgress.advance(terminal, clock);
        falsity(terminalProgress.advance(terminal, clock), "matching terminal patrol does not loop");
        equal(2, terminalProgress.checkpointIndex(), "same-target terminal patrol advances to endpoint");

        expectIllegalArgument(() -> route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of(
                Checkpoint.alert(), Checkpoint.patrolMove(new Vec2f(1.5f, 0.5f)))), "checkpoint 0");
    }

    private static void patrolLoopStopsCheckpointScan() {
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(4.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(0.5f, 0.5f)),
                Checkpoint.patrolMove(new Vec2f(0.51f, 0.5f))));
        PathfindingSimulator simulator = new PathfindingSimulator(new GridMap(5, 1), route, UnitConfig.normalGround(1f));

        FrameTrace first = simulator.tick(0L);
        equal(0, simulator.unit().routeProgress().checkpointIndex(),
                "terminal patrol returns to checkpoint zero after one loop");
        equal("complete MOVE, complete PATROL_MOVE", first.transition(),
                "one frame completes at most one patrol loop");

        FrameTrace second = simulator.tick(1L);
        equal("complete MOVE, complete PATROL_MOVE", second.transition(),
                "the next patrol cycle is evaluated on the next frame");
    }

    private static void waitAndAlertBehavior() {
        GridMap map = new GridMap(12, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(10.5f, 0.5f), List.of(
                Checkpoint.alert(),
                Checkpoint.waitForSeconds(0.05f)));
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        simulator.unit().setInertiaVelocity(new Vec2f(0.5f, 0f));
        Vec2f initialPosition = simulator.unit().cursorPosition();

        truth(simulator.unit().alertsShown() == 1, "entering ALERT invokes its side effect exactly once");
        FrameTrace first = simulator.tick(0L);
        equal(initialPosition, simulator.unit().cursorPosition(), "non-movement checkpoint does not integrate stale inertia");
        equal(new Vec2f(0.5f, 0f), simulator.unit().inertiaVelocity(), "waiting keeps existing inertia vector unchanged");
        truth(first.avoidanceRecomputed(), "waiting MOVE state still refreshes avoidance on phase frame");
        equal(1, simulator.unit().routeProgress().checkpointIndex(), "ALERT advances into WAIT checkpoint");
        simulator.tick(1L);
        equal(initialPosition, simulator.unit().cursorPosition(), "wait frame holds position");
        simulator.tick(2L);
        equal(2, simulator.unit().routeProgress().checkpointIndex(), "elapsed wait advances after duration");
        equal(1, simulator.unit().alertsShown(), "ALERT is not repeated on later ticks");
    }

    private static void stageClockWaitCheckpointKinds() {
        GridMap map = new GridMap(4, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of(
                Checkpoint.waitForPlayTime(1f),
                Checkpoint.waitForFragmentTime(1f),
                Checkpoint.waitForWaveTime(1f),
                Checkpoint.waitForBossRushArea(2),
                Checkpoint.alert()));
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        simulator.clock().setPlayTime(1f);
        simulator.clock().resetFragmentTime();
        simulator.clock().resetWaveTime();
        for (int index = 0; index < 30; index++) {
            simulator.clock().tick();
        }
        simulator.clock().setBossRushArea(2);
        simulator.tick(0L);
        equal(5, simulator.unit().routeProgress().checkpointIndex(), "all stage-clock waits complete with their supplied clock state");
        equal(1, simulator.unit().alertsShown(), "ALERT after waits executes when entered");
    }

    private static void ignoredCheckpoints() {
        Route skipped = new Route(
                new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f),
                List.of(Checkpoint.alert(), Checkpoint.waitForSeconds(100f), Checkpoint.move(new Vec2f(2.5f, 0.5f))),
                MovementMode.GROUND, true, true, true);
        PathfindingSimulator skippedSimulator = new PathfindingSimulator(
                new GridMap(4, 1), skipped, UnitConfig.normalGround(1f));
        equal(2, skippedSimulator.unit().routeProgress().checkpointIndex(),
                "ignore mode activates the first movement checkpoint directly");
        equal(0, skippedSimulator.unit().alertsShown(), "ignored ALERT has no side effect");
        FrameTrace move = skippedSimulator.tick(0L);
        truth(move.appliedDisplacement().x() > 0f, "ignored WAIT does not hold movement");

        Route portal = new Route(
                new Vec2f(0.5f, 0.5f), new Vec2f(2.5f, 0.5f),
                List.of(Checkpoint.disappear(), Checkpoint.waitForSeconds(100f),
                        Checkpoint.appearAt(new Vec2f(1.5f, 0.5f))),
                MovementMode.GROUND, true, true, true);
        PathfindingSimulator portalSimulator = new PathfindingSimulator(
                new GridMap(3, 1), portal, UnitConfig.normalGround(1f));
        FrameTrace portalFrame = portalSimulator.tick(0L);
        truth(portalFrame.transition().contains("complete DISAPPEAR"), "included disappearance completes");
        truth(portalFrame.transition().contains("complete APPEAR_AT_POS"), "included appearance completes");
        equal(new Vec2f(1.5f, 0.5f), portalSimulator.unit().cursorPosition(),
                "ignored wait does not interrupt disappearance-to-appearance relocation");
        equal(UnitMode.MOVE, portalSimulator.unit().mode(), "appearance restores movement in ignore mode");
    }

    private static void portalPreservesInertia() {
        GridMap map = new GridMap(8, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(6.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(1.5f, 0.5f)),
                Checkpoint.disappear(),
                Checkpoint.waitForSeconds(0.1f),
                Checkpoint.appearAt(new Vec2f(4.5f, 0.5f)),
                Checkpoint.move(new Vec2f(5.5f, 0.5f))));
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));

        Vec2f beforePortal = null;
        FrameTrace appearance = null;
        for (long globalFrame = 0L; globalFrame < 500L; globalFrame++) {
            FrameTrace trace = simulator.tick(globalFrame);
            if (simulator.unit().mode() == UnitMode.VANISHED && beforePortal == null) {
                beforePortal = simulator.unit().inertiaVelocity();
            }
            if (trace.transition().contains("complete APPEAR_AT_POS")) {
                appearance = trace;
                break;
            }
        }
        truth(beforePortal != null, "route reaches disappearance checkpoint");
        truth(appearance != null, "valid disappear route reaches appearance checkpoint");
        equal(new Vec2f(4.5f, 0.5f), appearance.cursorAfter(), "appearance relocates cursor to portal point");
        equal(beforePortal, simulator.unit().inertiaVelocity(), "portal preserves inertia velocity exactly");
        equal(UnitMode.MOVE, simulator.unit().mode(), "appearance restores MOVE state");
    }

    private static void earlyEndpointCompletionHidesCurrentCheckpoint() {
        Route route = new Route(
                new Vec2f(0.5f, 0.5f), new Vec2f(0.5f, 0.5f),
                List.of(Checkpoint.move(new Vec2f(2.5f, 0.5f))),
                MovementMode.GROUND, true, false, false);
        PathfindingSimulator simulator = new PathfindingSimulator(new GridMap(3, 1), route, UnitConfig.normalGround(1f));

        simulator.tick(0L);
        RouteProgress progress = simulator.unit().routeProgress();
        truth(progress.completed(), "endpoint may complete before optional checkpoints");
        equal(0, progress.checkpointIndex(), "early completion preserves the audit checkpoint index");
        equal(null, progress.current(route), "completed routes do not expose a stale active checkpoint");
    }

    private static void parameterValidation() {
        expectIllegalArgument(() -> new GridMap(0, 1), "Map dimensions must be positive");
        GridMap map = new GridMap(1, 1);
        expectNullPointer(() -> map.setRule(new TileCoord(0, 0), null), "Tile rule is required");
        expectIllegalArgument(() -> new TileRule(true, true, 0f, 1f,
                false, false, false, false, false, false), "groundEntryCost must be positive");
        TileRule infinityCost = new TileRule(true, true, Float.POSITIVE_INFINITY, 1f,
                false, false, false, false, false, false);
        truth(Float.isInfinite(infinityCost.groundEntryCost()), "positive infinity cost remains legal");
        expectIllegalArgument(() -> Checkpoint.move(new Vec2f(0.5f, 0.5f), -0.01f), "radius");
        expectIllegalArgument(() -> Checkpoint.waitForSeconds(-0.01f), "value");
        expectIllegalArgument(() -> Checkpoint.waitForBossRushArea(-1), "area");
        expectIllegalArgument(() -> new UnitConfig(Float.NaN, 1f, 1f, 1f,
                Vec2f.ZERO, Vec2f.ZERO, 0f, false, false, false), "finite");
        expectIllegalArgument(() -> new UnitConfig(1f, 1f, 1f, 1f,
                null, Vec2f.ZERO, 0f, false, false, false), "offsets");
        expectIllegalArgument(() -> new Route(null, new Vec2f(0.5f, 0.5f), List.of(),
                MovementMode.GROUND, true, true, false), "Route requires");
    }

    private static Route route(Vec2f spawn, Vec2f endpoint, List<Checkpoint> checkpoints) {
        return new Route(spawn, endpoint, checkpoints, MovementMode.GROUND, true, true, false);
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

    private static void expectIllegalArgument(Runnable action, String messagePart) {
        try {
            action.run();
        } catch (IllegalArgumentException error) {
            truth(error.getMessage() != null && error.getMessage().contains(messagePart),
                    "exception message should contain '" + messagePart + "': " + error.getMessage());
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException containing '" + messagePart + "'");
    }

    private static void expectNullPointer(Runnable action, String messagePart) {
        try {
            action.run();
        } catch (NullPointerException error) {
            truth(error.getMessage() != null && error.getMessage().contains(messagePart),
                    "exception message should contain '" + messagePart + "': " + error.getMessage());
            return;
        }
        throw new AssertionError("Expected NullPointerException containing '" + messagePart + "'");
    }

    private static void truth(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void falsity(boolean condition, String message) {
        truth(!condition, message);
    }

    private static void same(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected same object");
        }
    }

    private static void notSame(Object first, Object second, String message) {
        if (first == second) {
            throw new AssertionError(message + ": expected distinct objects");
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
        }
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
        }
    }

    private static void equal(long expected, long actual, String message) {
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
