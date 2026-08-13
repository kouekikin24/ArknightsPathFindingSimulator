/**
 * Integer tile coordinates. The center of (x, y) is (x + .5, y + .5).
 * Tile (x, y) owns the half-open map-space region [x, x + 1) x [y, y + 1),
 * so positions on an integer grid line belong to the tile on its positive side.
 */
public record TileCoord(int x, int y) {
    public static final TileCoord UP = new TileCoord(0, -1);
    public static final TileCoord RIGHT = new TileCoord(1, 0);
    public static final TileCoord DOWN = new TileCoord(0, 1);
    public static final TileCoord LEFT = new TileCoord(-1, 0);
    public static final TileCoord[] CARDINALS_UP_RIGHT_DOWN_LEFT = {UP, RIGHT, DOWN, LEFT};

    public TileCoord add(TileCoord other) {
        return new TileCoord(x + other.x, y + other.y);
    }

    public Vec2f center() {
        return new Vec2f(x + 0.5f, y + 0.5f);
    }

    /** Maps a position to the unique tile whose half-open region contains it. */
    public static TileCoord fromPosition(Vec2f position) {
        return new TileCoord(
                (int) Math.floor(position.x()),
                (int) Math.floor(position.y()));
    }
}
