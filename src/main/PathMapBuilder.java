import java.util.ArrayDeque;
import java.util.Arrays;

/** Deterministic reverse-SPFA builder plus in-place nextNode smoothing. */
public final class PathMapBuilder {
    public PathMap build(GridMap map, TileCoord target, MovementMode mode, boolean allowDiagonalMove) {
        if (!map.contains(target)) {
            throw new IllegalArgumentException("Target is outside the map: " + target);
        }

        int size = map.width() * map.height();
        float[] distances = new float[size];
        int[] next = new int[size];
        boolean[] queued = new boolean[size];
        Arrays.fill(distances, Float.POSITIVE_INFINITY);
        Arrays.fill(next, -1);

        int targetIndex = map.index(target);
        distances[targetIndex] = 0f;
        next[targetIndex] = targetIndex;

        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(targetIndex);
        queued[targetIndex] = true;

        while (!queue.isEmpty()) {
            int currentIndex = queue.removeFirst();
            queued[currentIndex] = false;
            TileCoord current = map.coordinate(currentIndex);

            for (TileCoord direction : TileCoord.CARDINALS_UP_RIGHT_DOWN_LEFT) {
                TileCoord scanned = current.add(direction);
                if (!map.contains(scanned) || !map.canTraverse(scanned, current, mode)) {
                    continue;
                }

                int scannedIndex = map.index(scanned);
                float candidate = distances[currentIndex] + map.rule(scanned).entryCost(mode);
                if (candidate < distances[scannedIndex]) {
                    distances[scannedIndex] = candidate;
                    next[scannedIndex] = currentIndex;
                    if (!queued[scannedIndex]) {
                        queue.addLast(scannedIndex);
                        queued[scannedIndex] = true;
                    }
                }
            }
        }

        for (int index = 0; index < size; index++) {
            TileCoord coordinate = map.coordinate(index);
            if (!map.passable(coordinate, mode)) {
                next[index] = index;
            }
        }

        int[] rawNext = next.clone();
        NextNodeSmoother.smooth(map, mode, next, allowDiagonalMove);
        return new PathMap(map, target, mode, distances, rawNext, next);
    }
}
