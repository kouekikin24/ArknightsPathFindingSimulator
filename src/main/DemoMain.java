import java.util.List;
import java.util.Locale;

/** Small command-line audit of the two-checkpoint example discussed in the conversation. */
public final class DemoMain {
    private DemoMain() {
    }

    public static void main(String[] args) {
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

        for (long globalFrame = 0L;
             globalFrame < 240L && simulator.unit().mode() != UnitMode.COMPLETED;
             globalFrame++) {
            FrameTrace trace = simulator.tick(globalFrame);
            if (trace.frame() < 5 || !trace.transition().isEmpty() || trace.frame() % 30 == 0) {
                System.out.printf(Locale.ROOT,
                        "frame=%d cp=%d pos=(%.7f,%.7f) velocity=(%.7f,%.7f) transition=%s%n",
                        trace.frame(), trace.checkpointIndex(),
                        trace.cursorAfter().x(), trace.cursorAfter().y(),
                        trace.inertiaAfter().x(), trace.inertiaAfter().y(), trace.transition());
            }
        }
    }
}
