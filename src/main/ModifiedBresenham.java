/**
 * The line test used by nextNode smoothing. It is stricter than textbook
 * supercover: a diagonal step rejects either blocked corner, and a span of one
 * tile on either axis checks the complete 2xN or Nx2 narrow band. The narrow
 * band rule is intentionally discontinuous with the general walk.
 */
public final class ModifiedBresenham {
    private ModifiedBresenham() {
    }

    public static boolean canLink(GridMap map, MovementMode mode, TileCoord start, TileCoord end) {
        int dx = end.x() - start.x();
        int dy = end.y() - start.y();
        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);

        if (absDx == 1 || absDy == 1) {
            return wholeNarrowBandClear(map, mode, start, end);
        }

        int stepX = Integer.compare(dx, 0);
        int stepY = Integer.compare(dy, 0);
        int x = start.x();
        int y = start.y();
        int error = absDx - absDy;

        while (true) {
            if (blocked(map, mode, x, y)) {
                return false;
            }
            if (x == end.x() && y == end.y()) {
                return true;
            }

            int twiceError = error * 2;
            boolean advanceX = twiceError >= -absDy;
            boolean advanceY = twiceError <= absDx;
            int previousX = x;
            int previousY = y;

            if (advanceX) {
                error -= absDy;
                x += stepX;
            }
            if (advanceY) {
                error += absDx;
                y += stepY;
            }

            if (advanceX && advanceY) {
                if (blocked(map, mode, previousX + stepX, previousY)
                        || blocked(map, mode, previousX, previousY + stepY)) {
                    return false;
                }
            }
        }
    }

    /**
     * Confirmed article rule: when either axis spans one tile, every tile in
     * the complete 2xN/Nx2 narrow band is checked. This deliberately remains
     * distinct from a conventional Bresenham corridor or a smaller heuristic.
     */
    private static boolean wholeNarrowBandClear(GridMap map, MovementMode mode, TileCoord start, TileCoord end) {
        int minX = Math.min(start.x(), end.x());
        int maxX = Math.max(start.x(), end.x());
        int minY = Math.min(start.y(), end.y());
        int maxY = Math.max(start.y(), end.y());
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (blocked(map, mode, x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean blocked(GridMap map, MovementMode mode, int x, int y) {
        return map.smoothingBlocked(new TileCoord(x, y), mode);
    }
}
