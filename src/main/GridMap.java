import java.util.Arrays;
import java.util.Objects;

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
        Objects.requireNonNull(coordinate, "Tile coordinate is required");
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
        if (index < 0 || index >= rules.length) {
            throw new IllegalArgumentException("Outside map index: " + index);
        }
        return new TileCoord(index % width, index / width);
    }

    public TileRule rule(TileCoord coordinate) {
        return rules[index(coordinate)];
    }

    public void setRule(TileCoord coordinate, TileRule rule) {
        rules[index(coordinate)] = Objects.requireNonNull(rule, "Tile rule is required");
        version++;
    }

    public boolean passable(TileCoord coordinate, MovementMode mode) {
        Objects.requireNonNull(mode, "Movement mode is required");
        // Confirmed movement rule: flying units ignore every terrain obstacle
        // and terrain-specific traversal flag, but never leave the stage map.
        return contains(coordinate) && (mode == MovementMode.FLYING || rule(coordinate).passable(mode));
    }

    /** Returns the terrain-entry cost used by pathfinding for one movement mode. */
    public float entryCost(TileCoord coordinate, MovementMode mode) {
        Objects.requireNonNull(coordinate, "Tile coordinate is required");
        Objects.requireNonNull(mode, "Movement mode is required");
        if (!contains(coordinate)) {
            throw new IllegalArgumentException("Outside map: " + coordinate);
        }
        // Flying terrain has no routing cost distinction: all in-map cells cost one.
        return mode == MovementMode.FLYING ? TileRule.NORMAL_COST : rule(coordinate).entryCost(mode);
    }

    public boolean canTraverse(TileCoord from, TileCoord to, MovementMode mode) {
        Objects.requireNonNull(mode, "Movement mode is required");
        return passable(from, mode) && passable(to, mode);
    }

    public boolean smoothingBlocked(TileCoord coordinate, MovementMode mode) {
        Objects.requireNonNull(coordinate, "Tile coordinate is required");
        Objects.requireNonNull(mode, "Movement mode is required");
        if (mode == MovementMode.FLYING) {
            return !contains(coordinate);
        }
        return !contains(coordinate) || rule(coordinate).smoothingBlocked(mode) || !passable(coordinate, mode);
    }

    public boolean avoidanceBlocked(TileCoord coordinate, MovementMode mode) {
        Objects.requireNonNull(coordinate, "Tile coordinate is required");
        Objects.requireNonNull(mode, "Movement mode is required");
        if (mode == MovementMode.FLYING) {
            return false;
        }
        return !contains(coordinate) || rule(coordinate).avoidanceBlocked(mode) || !passable(coordinate, mode);
    }

    public boolean collisionBlocked(TileCoord coordinate, MovementMode mode) {
        Objects.requireNonNull(coordinate, "Tile coordinate is required");
        Objects.requireNonNull(mode, "Movement mode is required");
        if (mode == MovementMode.FLYING) {
            return !contains(coordinate);
        }
        return !contains(coordinate) || rule(coordinate).collisionBlocked(mode) || !passable(coordinate, mode);
    }
}
