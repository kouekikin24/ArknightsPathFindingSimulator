import java.util.List;

/** Immutable data consumed by the visual layer. It contains no mutable core objects. */
public record UiSnapshot(
        int width,
        int height,
        List<UiTerrain> terrain,
        UiPoint spawn,
        UiPoint endpoint,
        List<UiCheckpoint> checkpoints,
        UiMovementMode movementMode,
        float attributeSpeed,
        boolean allowDiagonalMove,
        int frame,
        String unitMode,
        int activeCheckpoint,
        boolean completed,
        boolean terminal,
        UiPoint entityPosition,
        UiPoint cursorPosition,
        UiPoint inertiaVelocity,
        UiPoint avoidance,
        UiPoint givenDirection,
        UiCell cursorTile,
        UiCell nextNode,
        UiPoint target,
        boolean avoidanceRecomputed,
        String transition,
        float playTime,
        List<UiPathSegment> pathSegments,
        boolean trajectoryBreak) {

    public UiSnapshot {
        terrain = List.copyOf(terrain);
        checkpoints = List.copyOf(checkpoints);
        pathSegments = List.copyOf(pathSegments);
    }

    public UiTerrain terrainAt(UiCell cell) {
        if (cell.x() < 0 || cell.x() >= width || cell.y() < 0 || cell.y() >= height) {
            throw new IndexOutOfBoundsException("Outside snapshot: " + cell);
        }
        return terrain.get(cell.y() * width + cell.x());
    }
}
