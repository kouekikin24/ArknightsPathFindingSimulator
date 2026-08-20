import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Dependency-free executable regression suite for the confirmed simulator rules. */
public final class SimulatorTests {
    private static int passed;

    public static void main(String[] args) {
        if (args.length == 1 && "--baseline".equals(args[0])) {
            printBaseline();
            return;
        }
        run("tile ownership uses floor for lines and negative positions", SimulatorTests::tileOwnershipUsesFloor);
        run("Unity-style vector normalization and clamp", SimulatorTests::unityVectorOperations);
        run("projection guard compares squared length to squared tolerance", SimulatorTests::projectionGuardDimension);
        run("avoidance normalizes then removes given-direction projection", SimulatorTests::avoidanceNormalizationAndProjection);
        run("avoidance derives its current tile from the entity position", SimulatorTests::avoidanceUsesEntityTile);
        run("reverse SPFA charges the entered tile", SimulatorTests::nonUniformSpfaCosts);
        run("modified Bresenham blocks corner cutting", SimulatorTests::modifiedBresenhamCorner);
        run("modified Bresenham checks confirmed narrow bands", SimulatorTests::modifiedBresenhamNarrowBand);
        run("nextNode smoothing is ordered and in place", SimulatorTests::inPlaceNextNodeSmoothing);
        run("orthogonal smoothing never creates a diagonal next node", SimulatorTests::orthogonalSmoothing);
        run("external global frames control avoidance cadence", SimulatorTests::globalFrameCadence);
        run("bound and outside-map MOVE frames still refresh avoidance", SimulatorTests::boundAndOutsideAvoidanceCadence);
        run("movement uses continuous integration without position snapping", SimulatorTests::movementDoesNotSnap);
        run("centre-visit policies are explicit opt-in", SimulatorTests::visitPolicyIsOptIn);
        run("outside-map entry uses the complete movement pipeline", SimulatorTests::outsideMapEntryUsesFullPipeline);
        run("outside-map spawn preserves movement checkpoints", SimulatorTests::outsideMapSpawnPreservesCheckpoints);
        run("unreachable movement checkpoint enters blocked once", SimulatorTests::unreachableMoveBlocks);
        run("unreachable endpoint enters blocked once", SimulatorTests::unreachableEndpointBlocks);
        run("swept collision slides instead of reflecting", SimulatorTests::sweptCollisionSlide);
        run("swept collision handles corners, long sweeps, and bounds", SimulatorTests::sweptCollisionCornerLongSweepAndBounds);
        run("swept collision orders near-simultaneous crossings strictly", SimulatorTests::sweptCollisionUsesStrictCrossingOrder);
        run("swept collision rejects non-finite and runaway input", SimulatorTests::sweptCollisionInputValidation);
        run("path maps stay immutable and cache evicts only stale map versions", SimulatorTests::pathMapImmutabilityAndCacheEviction);
        run("path maps are self-contained after construction", SimulatorTests::pathMapIsSelfContained);
        run("route-owned distances separate same tile goals and refresh on map change", SimulatorTests::routeOwnedDistances);
        run("invalid disappear paths are rejected", SimulatorTests::invalidDisappearPaths);
        run("cursor history records the previous distinct tile", SimulatorTests::cursorHistoryUsesDistinctTiles);
        run("same-tile route target switch resets node-visit history", SimulatorTests::sameTileTargetSwitchResetsVisitHistory);
        run("trace ring buffer retains order and listener receives all frames", SimulatorTests::traceRingBufferAndListener);
        run("flying mode ignores all terrain obstacles", SimulatorTests::flyingMode);
        run("confirmed patrol loop rule and loop distance", SimulatorTests::patrolLoopRule);
        run("patrol loop stops checkpoint scanning for the current frame", SimulatorTests::patrolLoopStopsCheckpointScan);
        run("wait checkpoints hold inertia while still refreshing avoidance", SimulatorTests::waitAndAlertBehavior);
        run("stun holds position for its rounded duration and expires", SimulatorTests::stunHoldsThenExpires);
        run("displacement pushes against steering and respects collision", SimulatorTests::displacementPushesAndCollides);
        run("bound units hold position until released", SimulatorTests::boundHoldsPosition);
        run("combat injections reject invalid input and terminal modes", SimulatorTests::combatInjectionValidation);
        run("stage shares path maps and the global avoidance phase", SimulatorTests::stageSharesCacheAndPhase);
        run("stage units are independent of their companions", SimulatorTests::stageUnitsAreIndependent);
        run("stage validates input and frame sequence", SimulatorTests::stageValidation);
        run("all stage-clock wait checkpoint kinds advance", SimulatorTests::stageClockWaitCheckpointKinds);
        run("stage clock derives time from an integer frame counter", SimulatorTests::stageClockDerivesTimeFromFrames);
        run("ignored checkpoints skip side effects and preserve portals", SimulatorTests::ignoredCheckpoints);
        run("portal relocation preserves inertia velocity", SimulatorTests::portalPreservesInertia);
        run("a vanished unit cannot complete the endpoint", SimulatorTests::vanishedUnitCannotCompleteEndpoint);
        run("early endpoint completion hides remaining checkpoints", SimulatorTests::earlyEndpointCompletionHidesCurrentCheckpoint);
        run("endpoint and checkpoint radii share one constant", SimulatorTests::sharedCompletionRadius);
        run("identical scenarios produce bit-identical trajectories", SimulatorTests::deterministicBaseline);
        run("parameter validation rejects invalid input", SimulatorTests::parameterValidation);
        run("avoidance pushes away from blocked neighbors within the margin", SimulatorTests::avoidanceNeighborInfluence);
        run("tile-center visit policy detours through every entered tile center", SimulatorTests::visitEveryTileCenterSteering);
        run("route distances continue from the last portal and past a terminal portal", SimulatorTests::portalRouteDistances);
        run("a move checkpoint after a portal completes from the relocated position", SimulatorTests::postPortalCompletionUsesRelocatedPosition);
        run("vanished and completed units reject combat injections", SimulatorTests::injectionRejectsVanishedAndCompleted);
        run("setPlayTime rebases the clock at any frame without a jump", SimulatorTests::setPlayTimeAtNonzeroFrame);
        run("a capacity-one trace ring still delivers every frame to the listener", SimulatorTests::traceCapacityOne);
        run("a huge stun duration clamps instead of overflowing the expiry frame", SimulatorTests::hugeStunDurationClamps);
        run("a sub-epsilon displacement is stationary instead of clipping walls", SimulatorTests::subEpsilonDisplacementIsStationary);
        run("map dimensions stay within the allocation bound", SimulatorTests::mapDimensionBounds);
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

    private static void projectionGuardDimension() {
        Vec2f direction = new Vec2f(0.0001f, 0f);
        Vec2f projection = new Vec2f(1f, 2f).projectOnto(direction);
        equal(1f, projection.x(), 0.00001f, "squared denominator keeps a valid projection");
        equal(0f, projection.y(), 0f, "projection remains parallel to the direction");
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

    private static void avoidanceUsesEntityTile() {
        GridMap map = new GridMap(3, 1);
        map.setRule(new TileCoord(1, 0), TileRule.impassable());
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(2.5f, 0.5f), List.of());
        UnitConfig config = new UnitConfig(1f, 0.5f, 8f, 10f,
                new Vec2f(1f, 0f), Vec2f.ZERO, 0.2f, false, false, false);
        UnitState unit = new UnitState(route, config);

        Vec2f avoidance = new AvoidanceCalculator().calculate(
                map, MovementMode.GROUND, config, unit, Vec2f.ZERO);

        equal(new Vec2f(1f, 0f), avoidance,
                "avoidance finds the nearest passable tile from the entity's blocked tile");
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

    private static void visitPolicyIsOptIn() {
        UnitConfig plain = UnitConfig.normalGround(1f);
        falsity(plain.visitEveryNodeStably(), "default unit does not enable stable node visits");
        truth(plain.withVisitEveryNodeStably(true).visitEveryNodeStably(),
                "stable-node policy is explicitly reachable");

        GridMap map = new GridMap(6, 4);
        map.setRule(new TileCoord(2, 0), TileRule.impassable());
        map.setRule(new TileCoord(2, 1), TileRule.impassable());
        Route bent = route(new Vec2f(0.5f, 0.5f), new Vec2f(5.5f, 0.5f), List.of());
        PathfindingSimulator simulator = new PathfindingSimulator(map, bent, plain);
        for (long frame = 0L; frame < 900L && simulator.unit().mode() == UnitMode.MOVE; frame++) {
            FrameTrace trace = simulator.tick(frame);
            if (trace.nextNode() != null && trace.target() != null) {
                truth(trace.target().equals(trace.nextNode().center()) || trace.target().equals(bent.endpoint()),
                        "empty routes do not silently enable centre visiting");
            }
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

    private static void outsideMapEntryUsesFullPipeline() {
        GridMap map = new GridMap(4, 1);
        Route route = route(new Vec2f(-2.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of());
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        FrameTrace first = simulator.tick(0L);
        FrameTrace second = simulator.tick(1L);
        truth(first.appliedDisplacement().length() < UnitConfig.normalGround(1f).theoreticalSpeed() * F32.DT,
                "outside entry accelerates instead of jumping to full speed");
        truth(second.appliedDisplacement().length() > first.appliedDisplacement().length(),
                "outside entry accumulates inertia through the normal integrator");
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

    private static void sweptCollisionInputValidation() {
        GridMap map = new GridMap(2, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(1.5f, 0.5f), List.of());
        UnitState unit = new UnitState(route, UnitConfig.normalGround(1f));
        CollisionResolver resolver = new CollisionResolver();
        expectIllegalArgument(() -> resolver.resolve(map, MovementMode.GROUND, unit,
                new Vec2f(Float.NaN, 0f), Vec2f.ZERO), "finite");
        expectIllegalArgument(() -> resolver.resolve(map, MovementMode.GROUND, unit,
                new Vec2f(5_000f, 0f), new Vec2f(5_000f, 0f)), "cannot sweep");
    }

    private static void pathMapIsSelfContained() {
        GridMap map = new GridMap(3, 2);
        PathMap path = new PathMapBuilder().build(map, new TileCoord(2, 1), MovementMode.GROUND, true);
        equal(3, path.width(), "path map stores its width");
        equal(2, path.height(), "path map stores its height");
        truth(path.contains(new TileCoord(2, 1)), "path map owns valid coordinates");
        falsity(path.contains(new TileCoord(3, 1)), "path map rejects out-of-range coordinates");
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

    private static void stageClockDerivesTimeFromFrames() {
        StageClock clock = new StageClock();
        for (int index = 0; index < 10_000; index++) {
            clock.tick();
        }
        equal(10_000L, clock.frame(), "clock tracks integer frame count");
        equal(10_000L * F32.DT, clock.playTime(), 0f,
                "play time is one frame multiplication, not repeated accumulation");
        clock.resetFragmentTime();
        clock.tick();
        equal(F32.DT, clock.fragmentTime(), 0f, "fragment time is rebased by frame");
        expectIllegalArgument(() -> clock.setPlayTime(Float.NaN), "finite");
        expectIllegalArgument(() -> clock.setBossRushArea(-1), "non-negative");
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

    private static void vanishedUnitCannotCompleteEndpoint() {
        Route route = new Route(
                new Vec2f(0.5f, 0.5f), new Vec2f(0.5f, 0.5f),
                List.of(Checkpoint.disappear(), Checkpoint.waitForSeconds(1f),
                        Checkpoint.appearAt(new Vec2f(1.5f, 0.5f))),
                MovementMode.GROUND, true, false, false);
        PathfindingSimulator simulator = new PathfindingSimulator(new GridMap(3, 1), route,
                UnitConfig.normalGround(1f));
        simulator.tick(0L);
        equal(UnitMode.VANISHED, simulator.unit().mode(), "disappearance keeps the unit vanished during wait");
        falsity(simulator.unit().routeProgress().completed(), "vanished unit cannot complete endpoint");
    }

    private static void sharedCompletionRadius() {
        equal(Checkpoint.DEFAULT_MOVE_RADIUS, PathfindingSimulator.ENDPOINT_RADIUS, 0f,
                "endpoint radius uses checkpoint default");
        equal(Checkpoint.DEFAULT_MOVE_RADIUS, Checkpoint.move(new Vec2f(0.5f, 0.5f)).radius(), 0f,
                "MOVE default radius is shared");
        equal(Checkpoint.DEFAULT_MOVE_RADIUS, Checkpoint.patrolMove(new Vec2f(0.5f, 0.5f)).radius(), 0f,
                "PATROL_MOVE default radius is shared");
    }

    private static void deterministicBaseline() {
        List<Vec2f> first = baselineTrajectory();
        List<Vec2f> second = baselineTrajectory();
        equal(first.size(), second.size(), "baseline runs have equal length");
        for (int index = 0; index < first.size(); index++) {
            truth(Float.floatToIntBits(first.get(index).x()) == Float.floatToIntBits(second.get(index).x())
                            && Float.floatToIntBits(first.get(index).y()) == Float.floatToIntBits(second.get(index).y()),
                    "baseline frame " + index + " is bit-identical");
        }
    }

    private static List<Vec2f> baselineTrajectory() {
        GridMap map = new GridMap(6, 3);
        map.setRule(new TileCoord(3, 1), TileRule.box());
        Route route = route(new Vec2f(0.5f, 1.5f), new Vec2f(5.5f, 1.5f), List.of());
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        List<Vec2f> result = new ArrayList<>();
        for (long frame = 0; frame < 600 && simulator.unit().mode() != UnitMode.COMPLETED; frame++) {
            simulator.tick(frame);
            result.add(simulator.unit().entityPosition());
        }
        truth(simulator.unit().mode() == UnitMode.COMPLETED, "baseline scenario reaches endpoint");
        return result;
    }

    private static void printBaseline() {
        List<Vec2f> positions = baselineTrajectory();
        for (int frame = 0; frame < positions.size(); frame++) {
            Vec2f position = positions.get(frame);
            System.out.printf(Locale.ROOT, "%d\t%08x\t%08x\t%.7f\t%.7f%n", frame + 1,
                    Float.floatToIntBits(position.x()), Float.floatToIntBits(position.y()),
                    position.x(), position.y());
        }
    }

    private static void stunHoldsThenExpires() {
        GridMap map = new GridMap(8, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(7.5f, 0.5f), List.of());
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        simulator.tick(0L);
        Vec2f moving = simulator.unit().entityPosition();
        truth(moving.x() > 0.5f, "setup frame moves the unit");

        simulator.stun(1L, 0.1f);
        for (long frame = 1L; frame <= 3L; frame++) {
            FrameTrace trace = simulator.tick(frame);
            equal(UnitMode.STUNNED, trace.modeAfter(), "stun holds the mode for its duration");
            equal(moving, simulator.unit().entityPosition(), "stunned unit holds its position exactly");
        }
        FrameTrace resumed = simulator.tick(4L);
        equal(UnitMode.MOVE, resumed.modeAfter(), "stun expires into MOVE");
        truth(simulator.unit().entityPosition().x() > moving.x(), "unit resumes movement after expiry");
    }

    private static void displacementPushesAndCollides() {
        GridMap map = new GridMap(8, 3);
        Route route = route(new Vec2f(0.5f, 1.5f), new Vec2f(7.5f, 1.5f), List.of());
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        simulator.tick(0L);
        Vec2f before = simulator.unit().entityPosition();

        simulator.displace(1L, new Vec2f(0f, -3f), 0.15f);
        for (long frame = 1L; frame <= 5L; frame++) {
            FrameTrace trace = simulator.tick(frame);
            equal(UnitMode.DISPLACED, trace.modeAfter(), "displacement holds the mode for its duration");
        }
        Vec2f displaced = simulator.unit().entityPosition();
        equal(5f * -3f * F32.DT, displaced.y() - before.y(), 0.000001f,
                "constant velocity moves the unit each displaced frame");
        FrameTrace resumed = simulator.tick(6L);
        equal(UnitMode.MOVE, resumed.modeAfter(), "displacement expires into MOVE");

        GridMap walledMap = new GridMap(3, 3);
        walledMap.setRule(new TileCoord(1, 0), TileRule.impassable());
        PathfindingSimulator walled = new PathfindingSimulator(walledMap,
                route(new Vec2f(0.5f, 0.5f), new Vec2f(2.5f, 0.5f), List.of()), UnitConfig.normalGround(1f));
        walled.displace(0L, new Vec2f(1f, 0f), 0.1f);
        walled.tick(0L);
        truth(walled.unit().entityPosition().x() <= 1f, "displacement collision stops the unit at the wall");
    }

    private static void boundHoldsPosition() {
        GridMap map = new GridMap(8, 1);
        PathfindingSimulator simulator = new PathfindingSimulator(map,
                route(new Vec2f(0.5f, 0.5f), new Vec2f(7.5f, 0.5f), List.of()), UnitConfig.normalGround(1f));
        simulator.tick(0L);
        Vec2f moving = simulator.unit().entityPosition();
        truth(moving.x() > 0.5f, "unbound unit moves");

        simulator.setBound(true);
        for (long frame = 1L; frame <= 3L; frame++) {
            simulator.tick(frame);
            equal(moving, simulator.unit().entityPosition(), "bound unit holds position");
        }
        simulator.setBound(false);
        simulator.tick(4L);
        truth(simulator.unit().entityPosition().x() > moving.x(), "released unit resumes movement");
    }

    private static void combatInjectionValidation() {
        GridMap map = new GridMap(4, 1);
        PathfindingSimulator simulator = new PathfindingSimulator(map,
                route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of()), UnitConfig.normalGround(1f));
        expectIllegalArgument(() -> simulator.stun(0L, -0.1f), "non-negative");
        expectIllegalArgument(() -> simulator.displace(0L, new Vec2f(Float.NaN, 0f), 0.1f), "finite");
        simulator.tick(0L);
        expectIllegalArgument(() -> simulator.stun(2L, 0.1f), "next frame");

        GridMap blockedMap = new GridMap(3, 1);
        blockedMap.setRule(new TileCoord(1, 0), TileRule.impassable());
        PathfindingSimulator blocked = new PathfindingSimulator(blockedMap,
                route(new Vec2f(0.5f, 0.5f), new Vec2f(2.5f, 0.5f), List.of()), UnitConfig.normalGround(1f));
        blocked.tick(0L);
        try {
            blocked.stun(1L, 0.1f);
            throw new AssertionError("Stun into a blocked unit was accepted");
        } catch (IllegalStateException expected) {
            truth(expected.getMessage() != null && expected.getMessage().contains("BLOCKED"),
                    "blocked injection error is explicit");
        }
    }

    private static void stageSharesCacheAndPhase() {
        GridMap map = new GridMap(8, 1);
        Route sharedRoute = route(new Vec2f(0.5f, 0.5f), new Vec2f(7.5f, 0.5f), List.of());
        PathMapCache cache = new PathMapCache();
        Stage stage = new Stage(map, List.of(
                new Stage.StageUnit(sharedRoute, UnitConfig.normalGround(1f)),
                new Stage.StageUnit(sharedRoute, UnitConfig.normalGround(1f))), cache, 100);
        List<FrameTrace> traces = stage.tick(6L);
        equal(1, cache.entryCount(), "identical targets share one cached path map");
        truth(traces.get(0).avoidanceRecomputed(), "first unit refreshes on the shared phase frame");
        equal(traces.get(0).avoidanceRecomputed(), traces.get(1).avoidanceRecomputed(),
                "all stage units share the caller's global phase");
        equal(1, stage.simulator(1).frame(), "each unit keeps its own audit frame counter");

        PathMapCache separate = new PathMapCache();
        Stage mixed = new Stage(map, List.of(
                new Stage.StageUnit(sharedRoute, UnitConfig.normalGround(1f)),
                new Stage.StageUnit(route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of()),
                        UnitConfig.normalGround(1f))), separate, 100);
        mixed.tick(6L);
        equal(2, separate.entryCount(), "different targets cache one path map each");
    }

    private static void stageUnitsAreIndependent() {
        GridMap map = new GridMap(8, 1);
        Route solo = route(new Vec2f(0.5f, 0.5f), new Vec2f(7.5f, 0.5f), List.of());
        Route other = route(new Vec2f(1.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(2.5f, 0.5f))));
        Stage alone = new Stage(map, List.of(new Stage.StageUnit(solo, UnitConfig.normalGround(1f))));
        Stage crowded = new Stage(map, List.of(
                new Stage.StageUnit(solo, UnitConfig.normalGround(1f)),
                new Stage.StageUnit(other, UnitConfig.normalGround(1f))));
        for (long frame = 0L; frame < 90L; frame++) {
            Vec2f soloPosition = alone.tick(frame).get(0).entityAfter();
            Vec2f crowdedPosition = crowded.tick(frame).get(0).entityAfter();
            truth(Float.floatToIntBits(soloPosition.x()) == Float.floatToIntBits(crowdedPosition.x())
                            && Float.floatToIntBits(soloPosition.y()) == Float.floatToIntBits(crowdedPosition.y()),
                    "unit trajectory is bit-identical with companions at frame " + frame);
        }
    }

    private static void stageValidation() {
        GridMap map = new GridMap(4, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of());
        expectIllegalArgument(() -> new Stage(map, List.of()), "at least one");
        expectNullPointer(() -> new Stage.StageUnit(null, UnitConfig.normalGround(1f)), "Route is required");
        Stage stage = new Stage(map, List.of(new Stage.StageUnit(route, UnitConfig.normalGround(1f))));
        expectIllegalArgument(() -> stage.tick(-1L), "non-negative");
        stage.tick(0L);
        expectIllegalArgument(() -> stage.tick(2L), "consecutive");
    }

    private static void avoidanceNeighborInfluence() {
        AvoidanceCalculator calculator = new AvoidanceCalculator();
        UnitConfig config = UnitConfig.normalGround(1f);

        // UP wall at (1, 0), cursor at (1.2, 1.2): the foot's Y is exactly 1.0,
        // so the up face sits 0.8 away, inside the 0.25 + 0.2 influence band.
        GridMap upWall = new GridMap(4, 4);
        upWall.setRule(new TileCoord(1, 0), TileRule.impassable());
        Vec2f up = avoidanceAt(calculator, config, upWall, 1.2f, 1.2f, Vec2f.ZERO);
        equal(new Vec2f(0f, 1f), up, "up wall inside the margin pushes straight down");

        // Both the UP and LEFT neighbors blocked: each diagonal neighbor inside
        // the band contributes its averaged push, and the sum is normalized.
        GridMap corner = new GridMap(4, 4);
        corner.setRule(new TileCoord(1, 0), TileRule.impassable());
        corner.setRule(new TileCoord(0, 1), TileRule.impassable());
        Vec2f both = avoidanceAt(calculator, config, corner, 1.2f, 1.2f, Vec2f.ZERO);
        float diagonal = F32.sqrt(0.5f);
        equal(diagonal, both.x(), 0.000001f, "corner walls average into a diagonal push");
        equal(diagonal, both.y(), 0.000001f, "corner push is symmetric");
        equal(1f, both.length(), 0.000001f, "the 8-neighbor sum is normalized");
        Vec2f projected = avoidanceAt(calculator, config, corner, 1.2f, 1.2f, new Vec2f(1f, 0f));
        equal(0f, projected.x(), 0.000001f, "projection strips the given-direction component");
        equal(diagonal, projected.y(), 0.000001f, "the perpendicular avoidance component survives");

        // Diagonal neighbor alone: the averaged branch fires without any cardinal help.
        GridMap diagonalOnly = new GridMap(4, 4);
        diagonalOnly.setRule(new TileCoord(0, 0), TileRule.impassable());
        Vec2f diag = avoidanceAt(calculator, config, diagonalOnly, 1.2f, 1.2f, Vec2f.ZERO);
        equal(diagonal, diag.x(), 0.000001f, "a lone diagonal wall still pushes diagonally");
        equal(diagonal, diag.y(), 0.000001f, "lone diagonal push is symmetric");

        // Below the margin: from tile (0, 0) the wall's nearest face sits 1.25
        // from the foot, beyond 0.25 + 0.2, so it contributes nothing.
        Vec2f none = avoidanceAt(calculator, config, upWall, 0.5f, 0.5f, Vec2f.ZERO);
        equal(Vec2f.ZERO, none, "obstacle below the influence margin contributes nothing");
    }

    private static Vec2f avoidanceAt(AvoidanceCalculator calculator, UnitConfig config,
                                     GridMap map, float cursorX, float cursorY, Vec2f given) {
        Route spawn = route(new Vec2f(cursorX, cursorY), new Vec2f(3.5f, 3.5f), List.of());
        return calculator.calculate(map, MovementMode.GROUND, config, new UnitState(spawn, config), given);
    }

    private static void visitEveryTileCenterSteering() {
        GridMap map = new GridMap(5, 5);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(4.5f, 4.5f), List.of());
        UnitConfig visiting = new UnitConfig(1f, 0.5f, 8f, 10f, Vec2f.ZERO, new Vec2f(0f, -0.2f), 0.2f,
                true, false, false);
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, visiting);
        TileCoord previousTile = TileCoord.fromPosition(simulator.unit().cursorPosition());
        int centerDetours = 0;
        for (long globalFrame = 0L; globalFrame < 400L && simulator.unit().mode() == UnitMode.MOVE;
             globalFrame++) {
            FrameTrace trace = simulator.tick(globalFrame);
            if (trace.cursorTile().equals(previousTile)) {
                continue;
            }
            previousTile = trace.cursorTile();
            if (trace.target() == null || !trace.target().equals(previousTile.center())) {
                continue;
            }
            centerDetours++;
        }
        truth(centerDetours >= 3, "every newly entered tile detours to its center, saw " + centerDetours);
        equal(UnitMode.COMPLETED, simulator.unit().mode(), "the detouring unit still completes the route");

        UnitConfig nodeCenter = new UnitConfig(1f, 0.5f, 8f, 10f, Vec2f.ZERO, new Vec2f(0f, -0.2f), 0.2f,
                false, true, false);
        PathfindingSimulator control = new PathfindingSimulator(map, route, nodeCenter);
        TileCoord controlTile = TileCoord.fromPosition(control.unit().cursorPosition());
        for (long globalFrame = 0L; globalFrame < 400L && control.unit().mode() == UnitMode.MOVE;
             globalFrame++) {
            FrameTrace trace = control.tick(globalFrame);
            if (!trace.cursorTile().equals(controlTile)) {
                controlTile = trace.cursorTile();
                equal(route.endpoint(), trace.target(),
                        "node-center policy skips already-visited next nodes and keeps the endpoint");
            }
        }
    }

    private static void portalRouteDistances() {
        GridMap map = new GridMap(7, 1);
        TileCoord start = new TileCoord(0, 0);

        Route twoPortals = route(new Vec2f(0.5f, 0.5f), new Vec2f(0.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(2.5f, 0.5f)),
                Checkpoint.disappear(), Checkpoint.appearAt(new Vec2f(4.5f, 0.5f)),
                Checkpoint.disappear(), Checkpoint.appearAt(new Vec2f(6.5f, 0.5f))));
        PathfindingSimulator chained = new PathfindingSimulator(map, twoPortals, UnitConfig.normalGround(1f));
        equal(8f, chained.remainingRouteDistanceForCheckpoint(0, start), 0.000001f,
                "goal zero continues from the last portal between it and the endpoint");

        Route terminalPortal = route(new Vec2f(0.5f, 0.5f), new Vec2f(0.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(2.5f, 0.5f)),
                Checkpoint.disappear(), Checkpoint.appearAt(new Vec2f(4.5f, 0.5f))));
        PathfindingSimulator terminal = new PathfindingSimulator(map, terminalPortal, UnitConfig.normalGround(1f));
        equal(6f, terminal.remainingRouteDistanceForCheckpoint(0, start), 0.000001f,
                "a portal as the final checkpoint still originates the endpoint continuation");
    }

    private static void postPortalCompletionUsesRelocatedPosition() {
        GridMap map = new GridMap(8, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(6.5f, 0.5f), List.of(
                Checkpoint.move(new Vec2f(1.5f, 0.5f)),
                Checkpoint.disappear(),
                Checkpoint.appearAt(new Vec2f(4.5f, 0.5f)),
                Checkpoint.move(new Vec2f(5.5f, 0.5f))));
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        for (long globalFrame = 0L; globalFrame < 500L; globalFrame++) {
            FrameTrace trace = simulator.tick(globalFrame);
            if (trace.transition().contains("complete APPEAR_AT_POS")) {
                break;
            }
        }
        equal(new Vec2f(4.5f, 0.5f), simulator.unit().cursorPosition(), "portal relocated the cursor");
        FrameTrace completion = null;
        for (long globalFrame = simulator.lastGlobalFrame() + 1L; globalFrame < 600L; globalFrame++) {
            FrameTrace trace = simulator.tick(globalFrame);
            if (trace.transition().contains("complete MOVE")) {
                completion = trace;
                break;
            }
        }
        truth(completion != null, "the post-portal MOVE checkpoint completes");
        truth(completion.cursorAfter().x() > 4f,
                "completion is measured from the relocated position, not the pre-portal one");
    }

    private static void injectionRejectsVanishedAndCompleted() {
        PathfindingSimulator vanished = new PathfindingSimulator(new GridMap(4, 1),
                route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of(
                        Checkpoint.disappear(), Checkpoint.waitForSeconds(100f),
                        Checkpoint.appearAt(new Vec2f(2.5f, 0.5f)))),
                UnitConfig.normalGround(1f));
        vanished.tick(0L);
        equal(UnitMode.VANISHED, vanished.unit().mode(), "unit vanished on frame zero");
        expectIllegalState(() -> vanished.stun(1L, 1f), "Cannot inject");
        expectIllegalState(() -> vanished.displace(1L, new Vec2f(1f, 0f), 1f), "Cannot inject");

        PathfindingSimulator completed = new PathfindingSimulator(new GridMap(4, 1),
                route(new Vec2f(0.5f, 0.5f), new Vec2f(0.5f, 0.5f), List.of()), UnitConfig.normalGround(1f));
        completed.tick(0L);
        equal(UnitMode.COMPLETED, completed.unit().mode(), "unit spawned on its endpoint completes immediately");
        expectIllegalState(() -> completed.stun(1L, 1f), "Cannot inject");
        expectIllegalState(() -> completed.displace(1L, new Vec2f(1f, 0f), 1f), "Cannot inject");
    }

    private static void setPlayTimeAtNonzeroFrame() {
        StageClock clock = new StageClock();
        for (int index = 0; index < 100; index++) {
            clock.tick();
        }
        clock.setPlayTime(10f);
        equal(10f, clock.playTime(), 0.000001f, "rebased clock reads exactly the assigned time");
        clock.tick();
        equal(10f + F32.DT, clock.playTime(), 0.000001f, "the next tick continues from the new bias");
    }

    private static void traceCapacityOne() {
        GridMap map = new GridMap(4, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of());
        List<FrameTrace> heard = new ArrayList<>();
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f), 1, heard::add);
        for (long globalFrame = 0L; globalFrame < 5L; globalFrame++) {
            simulator.tick(globalFrame);
        }
        equal(5, heard.size(), "the listener receives every frame even at capacity one");
        equal(1, simulator.trace().size(), "the ring retains only the newest frame");
        equal(4, simulator.trace().getFirst().frame(), "the retained frame is the newest one");
        equal(4L, simulator.droppedTraceFrames(), "every older frame was evicted");
    }

    private static void hugeStunDurationClamps() {
        GridMap map = new GridMap(4, 1);
        Route route = route(new Vec2f(0.5f, 0.5f), new Vec2f(3.5f, 0.5f), List.of());
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        simulator.tick(0L);
        simulator.stun(1L, 65_000f);
        truth(simulator.unit().timedModeUntilGlobalFrame() > 0L,
                "the clamped expiry frame stays positive instead of wrapping negative");
        truth(simulator.unit().timedModeUntilGlobalFrame() <= (long) Integer.MAX_VALUE - 1L,
                "the expiry frame is tickable: the audit counter rejects frame == Integer.MAX_VALUE");

        // A saturating duration must land exactly on the last tickable frame...
        PathfindingSimulator saturated = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        saturated.tick(0L);
        saturated.stun(1L, Float.MAX_VALUE);
        equal((long) Integer.MAX_VALUE - 1L, saturated.unit().timedModeUntilGlobalFrame(),
                "a saturating duration clamps to the last tickable frame, not the rejected one");

        // ...even when the stage starts far beyond Integer.MAX_VALUE: the cap
        // follows the local counter, not the absolute global frame.
        PathfindingSimulator offset = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        offset.tick(5_000_000_000L);
        offset.stun(5_000_000_001L, Float.MAX_VALUE);
        equal(5_000_000_001L + (long) Integer.MAX_VALUE - 2L, offset.unit().timedModeUntilGlobalFrame(),
                "the clamp keys off the remaining local counter range");
    }

    private static void subEpsilonDisplacementIsStationary() {
        GridMap map = new GridMap(4, 1);
        map.setRule(new TileCoord(3, 0), TileRule.impassable());
        Route route = route(new Vec2f(2.9999995f, 0.5f), new Vec2f(2.5f, 0.5f), List.of());
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        // 2e-5 tiles/s * DT = 6.7e-7 per frame, below the float-noise threshold:
        // it must be treated as stationary instead of drifting through the wall.
        simulator.displace(0L, new Vec2f(2e-5f, 0f), 5f);
        for (long frame = 0L; frame <= 30L; frame++) {
            simulator.tick(frame);
        }
        equal(UnitMode.DISPLACED, simulator.unit().mode(),
                "a sub-epsilon push never latches the unit into the wall");
        equal(2.9999995f, simulator.unit().entityPosition().x(), 0f,
                "a sub-epsilon displacement stays put instead of clipping toward the wall");
        equal(new TileCoord(2, 0), TileCoord.fromPosition(simulator.unit().entityPosition()),
                "the unit never enters the wall tile");
    }

    private static void mapDimensionBounds() {
        expectIllegalArgument(() -> new GridMap(0, 4), "positive");
        expectIllegalArgument(() -> new GridMap(GridMap.MAXIMUM_DIMENSION + 1, 4), "at most");
        GridMap largest = new GridMap(GridMap.MAXIMUM_DIMENSION, 2);
        equal(GridMap.MAXIMUM_DIMENSION, largest.width(), "the bound itself is accepted");
    }

    private static void expectIllegalState(Runnable action, String messagePart) {
        try {
            action.run();
        } catch (IllegalStateException error) {
            truth(error.getMessage() != null && error.getMessage().contains(messagePart),
                    "exception message should contain '" + messagePart + "': " + error.getMessage());
            return;
        }
        throw new AssertionError("Expected IllegalStateException containing '" + messagePart + "'");
    }

    private static Route route(Vec2f spawn, Vec2f endpoint, List<Checkpoint> checkpoints) {        return new Route(spawn, endpoint, checkpoints, MovementMode.GROUND, true, true, false);
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
