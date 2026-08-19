import java.util.List;
import java.util.Objects;

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
        if (!Vec2f.isFinite(spawnCursorPosition) || !Vec2f.isFinite(endpoint) || movementMode == null) {
            throw new IllegalArgumentException("Route requires spawn, endpoint, and movement mode");
        }
        checkpoints = Objects.requireNonNull(checkpoints, "Route checkpoints are required");
        for (int index = 0; index < checkpoints.size(); index++) {
            if (checkpoints.get(index) == null) {
                throw new IllegalArgumentException("Route checkpoint " + index + " is required");
            }
        }
        checkpoints = List.copyOf(checkpoints);
        validateDisappearPaths(checkpoints);
        validateTerminalPatrolStart(checkpoints);
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

    /**
     * Confirmed article rule: only a terminal patrol move whose target differs
     * from checkpoint zero loops back to checkpoint zero.
     */
    public boolean hasTerminalPatrolLoop() {
        if (checkpoints.isEmpty()) {
            return false;
        }
        Checkpoint terminal = checkpoints.getLast();
        return terminal.type() == CheckpointType.PATROL_MOVE
                && !terminal.point().equals(checkpoints.getFirst().point());
    }

    private static void validateDisappearPaths(List<Checkpoint> checkpoints) {
        boolean vanished = false;
        int disappearIndex = -1;
        for (int index = 0; index < checkpoints.size(); index++) {
            Checkpoint checkpoint = checkpoints.get(index);
            if (checkpoint.type() == CheckpointType.DISAPPEAR) {
                if (vanished) {
                    throw invalidDisappearPath(disappearIndex);
                }
                vanished = true;
                disappearIndex = index;
            } else if (checkpoint.type() == CheckpointType.APPEAR_AT_POS) {
                vanished = false;
            } else if (vanished && checkpoint.type().isMovement()) {
                throw invalidDisappearPath(disappearIndex);
            }
        }
        if (vanished) {
            throw invalidDisappearPath(disappearIndex);
        }
    }

    private static IllegalArgumentException invalidDisappearPath(int checkpointIndex) {
        return new IllegalArgumentException("DISAPPEAR at checkpoint " + checkpointIndex
                + " must be followed by APPEAR_AT_POS before movement or route end");
    }

    private static void validateTerminalPatrolStart(List<Checkpoint> checkpoints) {
        if (checkpoints.isEmpty()) {
            return;
        }
        int terminalIndex = checkpoints.size() - 1;
        if (checkpoints.get(terminalIndex).type() == CheckpointType.PATROL_MOVE
                && checkpoints.getFirst().point() == null) {
            throw new IllegalArgumentException("Terminal PATROL_MOVE at checkpoint " + terminalIndex
                    + " requires checkpoint 0 to have a point");
        }
    }
}
