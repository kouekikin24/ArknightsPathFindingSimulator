import java.util.List;

/** Immutable route data shared by one or more units. */
public record Route(
        Vec2f spawnCursorPosition,
        Vec2f endpoint,
        List<Checkpoint> checkpoints,
        MovementMode movementMode,
        boolean allowDiagonalMove,
        boolean visitEveryCheckpoint,
        boolean ignoreAllButMoveCp) {

    public Route {
        if (spawnCursorPosition == null || endpoint == null || movementMode == null) {
            throw new IllegalArgumentException("Route requires spawn, endpoint, and movement mode");
        }
        checkpoints = List.copyOf(checkpoints);
    }

    public boolean hasNoCheckpoints() {
        return checkpoints.isEmpty();
    }

    public TileCoord endpointTile() {
        return TileCoord.fromPosition(endpoint);
    }

    public TileCoord targetTile(Checkpoint checkpoint) {
        return TileCoord.fromPosition(checkpoint.point());
    }
}
