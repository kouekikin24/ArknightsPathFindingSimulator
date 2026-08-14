/** Immutable navigation result for one target tile and movement mode. */
public final class PathMap {
    private final int width;
    private final int height;
    private final TileCoord target;
    private final MovementMode movementMode;
    private final float[] targetDistances;
    private final int[] rawNextIndices;
    private final int[] nextIndices;

    PathMap(int width, int height, TileCoord target, MovementMode movementMode,
            float[] targetDistances, int[] rawNextIndices, int[] nextIndices) {
        this.width = width;
        this.height = height;
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

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean contains(TileCoord coordinate) {
        return coordinate.x() >= 0 && coordinate.x() < width
                && coordinate.y() >= 0 && coordinate.y() < height;
    }

    private int index(TileCoord coordinate) {
        if (!contains(coordinate)) {
            throw new IllegalArgumentException("Outside path map: " + coordinate);
        }
        return coordinate.y() * width + coordinate.x();
    }

    private TileCoord coordinate(int index) {
        if (index < 0 || index >= width * height) {
            throw new IllegalArgumentException("Outside path map index: " + index);
        }
        return new TileCoord(index % width, index / width);
    }

    public float distanceToTarget(TileCoord coordinate) {
        return targetDistances[index(coordinate)];
    }

    public boolean reachable(TileCoord coordinate) {
        return contains(coordinate) && !Float.isInfinite(distanceToTarget(coordinate));
    }

    public TileCoord nextNode(TileCoord coordinate) {
        int next = nextIndices[index(coordinate)];
        return next < 0 ? null : coordinate(next);
    }

    public TileCoord rawNextNode(TileCoord coordinate) {
        int next = rawNextIndices[index(coordinate)];
        return next < 0 ? null : coordinate(next);
    }

    int[] copyNextIndices() {
        return nextIndices.clone();
    }
}
