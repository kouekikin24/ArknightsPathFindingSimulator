/** Integer tile coordinates. The center of (x, y) is (x + .5, y + .5). */
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

    /**
     * Tile centers are half-integers. Centered ties-to-even matches the
     * article's banker-rounding rule while preserving f32 drift around a grid line.
     */
    public static TileCoord fromPosition(Vec2f position) {
        return new TileCoord(
                F32.roundToEven(position.x() - 0.5f),
                F32.roundToEven(position.y() - 0.5f));
    }
}
