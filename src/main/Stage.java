import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates multiple deterministic units sharing one global frame phase
 * and one path-map cache. Units are independent: nothing here models
 * unit-to-unit interaction, so a unit's trajectory must not depend on which
 * other units share the stage.
 */
public final class Stage {
    private final List<PathfindingSimulator> simulators;
    private long lastGlobalFrame = Long.MIN_VALUE;

    public Stage(GridMap map, List<StageUnit> units) {
        this(map, units, new PathMapCache(), PathfindingSimulator.DEFAULT_TRACE_CAPACITY);
    }

    public Stage(GridMap map, List<StageUnit> units, PathMapCache cache, int traceCapacity) {
        Objects.requireNonNull(map, "Map is required");
        Objects.requireNonNull(units, "Stage units are required");
        Objects.requireNonNull(cache, "Path map cache is required");
        if (units.isEmpty()) {
            throw new IllegalArgumentException("A stage needs at least one unit");
        }
        List<PathfindingSimulator> built = new ArrayList<>(units.size());
        for (StageUnit unit : units) {
            built.add(new PathfindingSimulator(map, unit.route(), unit.config(),
                    cache, traceCapacity, null));
        }
        this.simulators = List.copyOf(built);
    }

    /** Ticks every unit with the shared global frame and returns one trace per unit in stage order. */
    public List<FrameTrace> tick(long globalFrame) {
        if (globalFrame < 0L) {
            throw new IllegalArgumentException("Global frame must be non-negative");
        }
        if (lastGlobalFrame != Long.MIN_VALUE && globalFrame != lastGlobalFrame + 1L) {
            throw new IllegalArgumentException("Global frames must be consecutive: expected "
                    + (lastGlobalFrame + 1L) + ", got " + globalFrame);
        }
        lastGlobalFrame = globalFrame;
        List<FrameTrace> traces = new ArrayList<>(simulators.size());
        for (PathfindingSimulator simulator : simulators) {
            traces.add(simulator.tick(globalFrame));
        }
        return List.copyOf(traces);
    }

    public int size() {
        return simulators.size();
    }

    public PathfindingSimulator simulator(int index) {
        return simulators.get(index);
    }

    public List<FrameTrace> trace(int index) {
        return simulators.get(index).trace();
    }

    public long lastGlobalFrame() {
        return lastGlobalFrame;
    }

    /** One route/configuration pair staged together; validated on construction. */
    public record StageUnit(Route route, UnitConfig config) {
        public StageUnit {
            Objects.requireNonNull(route, "Route is required");
            Objects.requireNonNull(config, "Unit config is required");
        }
    }
}
