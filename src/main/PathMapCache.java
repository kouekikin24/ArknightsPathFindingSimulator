import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Cache of immutable path maps grouped by weakly-held map instance. */
public final class PathMapCache {
    private final PathMapBuilder builder = new PathMapBuilder();
    private final Map<GridMap, VersionedMaps> byMap = new WeakHashMap<>();

    public PathMap get(GridMap map, TileCoord target, MovementMode mode, boolean allowDiagonalMove) {
        Objects.requireNonNull(map, "Map is required");
        Objects.requireNonNull(target, "Target is required");
        Objects.requireNonNull(mode, "Movement mode is required");

        long version = map.version();
        VersionedMaps versioned = byMap.get(map);
        if (versioned == null || versioned.version != version) {
            versioned = new VersionedMaps(version);
            byMap.put(map, versioned);
        }
        return versioned.maps.computeIfAbsent(
                new Key(target, mode, allowDiagonalMove),
                ignored -> builder.build(map, target, mode, allowDiagonalMove));
    }

    public void clear() {
        byMap.clear();
    }

    int entryCount() {
        int total = 0;
        for (VersionedMaps versioned : byMap.values()) {
            total += versioned.maps.size();
        }
        return total;
    }

    private static final class VersionedMaps {
        private final long version;
        private final Map<Key, PathMap> maps = new HashMap<>();

        private VersionedMaps(long version) {
            this.version = version;
        }
    }

    private record Key(TileCoord target, MovementMode mode, boolean allowDiagonalMove) {
    }
}
