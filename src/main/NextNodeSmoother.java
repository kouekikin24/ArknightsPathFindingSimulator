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
        if (allowDiagonalMove) {
            smoothDiagonal(map, mode, next, tester);
        } else {
            smoothOrthogonal(map, mode, next, tester);
        }
    }

    static void smoothDiagonal(GridMap map, MovementMode mode, int[] next, LinkTester tester) {
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
                    if (!tester.canLink(source, candidate)) {
                        break;
                    }
                    next[sourceIndex] = candidateIndex;
                }
            }
        }
    }

    private static void smoothOrthogonal(GridMap map, MovementMode mode, int[] next, LinkTester tester) {
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
                    if ((source.x() != candidate.x() && source.y() != candidate.y())
                            || !tester.canLink(source, candidate)) {
                        break;
                    }
                    next[sourceIndex] = candidateIndex;
                }
            }
        }
    }
}
