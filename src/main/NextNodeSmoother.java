/** Deterministic, in-place nextNode smoothing. */
public final class NextNodeSmoother {
    @FunctionalInterface
    interface LinkTester {
        boolean canLink(TileCoord source, TileCoord candidate);
    }

    private NextNodeSmoother() {
    }

    public static void smooth(GridMap map, MovementMode mode, int[] next, boolean allowDiagonalMove) {
        LinkTester tester = (source, candidate) -> ModifiedBresenham.canLink(map, mode, source, candidate);
        smoothInPlace(map, mode, next, tester, allowDiagonalMove);
    }

    /**
     * Rewrites next[source] in place, bottom row first, so earlier rewrites in
     * the same pass chain onto already-smoothed successors. When diagonal links
     * are disallowed, only same-row/same-column shortcuts are taken.
     */
    static void smoothInPlace(GridMap map, MovementMode mode, int[] next, LinkTester tester,
                              boolean allowDiagonal) {
        for (int y = map.height() - 1; y >= 0; y--) {
            for (int x = 0; x < map.width(); x++) {
                TileCoord source = new TileCoord(x, y);
                int sourceIndex = map.index(source);
                if (!map.passable(source, mode) || next[sourceIndex] < 0) {
                    continue;
                }

                while (true) {
                    int directIndex = next[sourceIndex];
                    if (directIndex < 0 || directIndex == sourceIndex) {
                        break;
                    }
                    int candidateIndex = next[directIndex];
                    if (candidateIndex < 0 || candidateIndex == directIndex || candidateIndex == sourceIndex) {
                        break;
                    }
                    TileCoord candidate = map.coordinate(candidateIndex);
                    if (!allowDiagonal && source.x() != candidate.x() && source.y() != candidate.y()) {
                        break;
                    }
                    if (!tester.canLink(source, candidate)) {
                        break;
                    }
                    next[sourceIndex] = candidateIndex;
                }
            }
        }
    }

    static void smoothDiagonal(GridMap map, MovementMode mode, int[] next, LinkTester tester) {
        smoothInPlace(map, mode, next, tester, true);
    }
}
