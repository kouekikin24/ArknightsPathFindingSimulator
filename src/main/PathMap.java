/** Immutable navigation result for one target tile and movement mode. */
public final class PathMap {
    private final GridMap map;
    private final TileCoord target;
    private final MovementMode movementMode;
    private final float[] targetDistances;
    private final int[] rawNextIndices;
    private final int[] nextIndices;

    PathMap(GridMap map, TileCoord target, MovementMode movementMode,
            float[] targetDistances, int[] rawNextIndices, int[] nextIndices) {
        this.map = map;
        this.target = target;
        this.movementMode = movementMode;
        this.targetDistances = targetDistances.clone();
        this.rawNextIndices = rawNextIndices.clone();
        this.nextIndices = nextIndices.clone();
    }

    public TileCoord target() {
        return target;
    }

    public MovementMode movementMode() {
        return movementMode;
    }

    public float distanceToTarget(TileCoord coordinate) {
        return targetDistances[map.index(coordinate)];
    }

    public boolean reachable(TileCoord coordinate) {
        return map.contains(coordinate) && !Float.isInfinite(distanceToTarget(coordinate));
    }

    public TileCoord nextNode(TileCoord coordinate) {
        int next = nextIndices[map.index(coordinate)];
        return next < 0 ? null : map.coordinate(next);
    }

    public TileCoord rawNextNode(TileCoord coordinate) {
        int next = rawNextIndices[map.index(coordinate)];
        return next < 0 ? null : map.coordinate(next);
    }

    int[] copyNextIndices() {
        return nextIndices.clone();
    }
}
