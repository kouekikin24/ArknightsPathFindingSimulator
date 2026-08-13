import java.util.HashMap;
import java.util.Map;

/** Cache key mirrors the game behavior: map version, movement mode, and target tile. */
public final class PathMapCache {
    private final PathMapBuilder builder = new PathMapBuilder();
    private final Map<Key, PathMap> maps = new HashMap<>();

    public PathMap get(GridMap map, TileCoord target, MovementMode mode, boolean allowDiagonalMove) {
        long version = map.version();
        maps.keySet().removeIf(existing -> existing.map() == map && existing.mapVersion() != version);
        Key key = new Key(map, version, target, mode, allowDiagonalMove);
        return maps.computeIfAbsent(key, ignored -> builder.build(map, target, mode, allowDiagonalMove));
    }

    public void clear() {
        maps.clear();
    }

    int entryCount() {
        return maps.size();
    }

    private record Key(GridMap map, long mapVersion, TileCoord target, MovementMode mode, boolean allowDiagonalMove) {
    }
}
