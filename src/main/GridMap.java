import java.util.Arrays;

/** Mutable, versioned map. Mutation invalidates cached path maps. */
public final class GridMap {
    private final int width;
    private final int height;
    private final TileRule[] rules;
    private long version;

    public GridMap(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Map dimensions must be positive");
        }
        this.width = width;
        this.height = height;
        this.rules = new TileRule[width * height];
        Arrays.fill(this.rules, TileRule.open());
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long version() {
        return version;
    }

    public boolean contains(TileCoord coordinate) {
        return coordinate.x() >= 0 && coordinate.x() < width
                && coordinate.y() >= 0 && coordinate.y() < height;
    }

    public int index(TileCoord coordinate) {
        if (!contains(coordinate)) {
            throw new IllegalArgumentException("Outside map: " + coordinate);
        }
        return coordinate.y() * width + coordinate.x();
    }

    public TileCoord coordinate(int index) {
        return new TileCoord(index % width, index / width);
    }

    public TileRule rule(TileCoord coordinate) {
        return rules[index(coordinate)];
    }

    public void setRule(TileCoord coordinate, TileRule rule) {
        rules[index(coordinate)] = rule;
        version++;
    }

    public boolean passable(TileCoord coordinate, MovementMode mode) {
        return contains(coordinate) && rule(coordinate).passable(mode);
    }

    public boolean canTraverse(TileCoord from, TileCoord to, MovementMode mode) {
        return passable(from, mode) && passable(to, mode);
    }

    public boolean smoothingBlocked(TileCoord coordinate, MovementMode mode) {
        return !contains(coordinate) || rule(coordinate).smoothingBlocked(mode) || !passable(coordinate, mode);
    }

    public boolean avoidanceBlocked(TileCoord coordinate, MovementMode mode) {
        return contains(coordinate) && (rule(coordinate).avoidanceBlocked(mode) || !passable(coordinate, mode));
    }

    public boolean collisionBlocked(TileCoord coordinate, MovementMode mode) {
        return !contains(coordinate) || rule(coordinate).collisionBlocked(mode) || !passable(coordinate, mode);
    }
}
