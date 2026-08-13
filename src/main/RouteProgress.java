/** Mutable checkpoint cursor. Checkpoint side effects are applied by PathfindingSimulator. */
public final class RouteProgress {
    private int checkpointIndex;
    private float enteredPlayTime;
    private boolean completed;

    public int checkpointIndex() {
        return checkpointIndex;
    }

    public boolean completed() {
        return completed;
    }

    public float enteredPlayTime() {
        return enteredPlayTime;
    }

    public Checkpoint current(Route route) {
        return !completed && checkpointIndex < route.checkpoints().size()
                ? route.checkpoints().get(checkpointIndex)
                : null;
    }

    public void enterAt(StageClock clock) {
        enteredPlayTime = clock.playTime();
    }

    /**
     * Confirmed article rule, not a general patrol convention: a terminal
     * patrol move returns to checkpoint zero only when its target differs
     * from the first checkpoint target. All other routes advance to endpoint.
     */
    public boolean advance(Route route, StageClock clock) {
        Checkpoint current = current(route);
        if (current != null && current.type() == CheckpointType.PATROL_MOVE
                && checkpointIndex == route.checkpoints().size() - 1
                && route.hasTerminalPatrolLoop()) {
            checkpointIndex = 0;
            enteredPlayTime = clock.playTime();
            return true;
        } else {
            checkpointIndex++;
        }
        enteredPlayTime = clock.playTime();
        return false;
    }

    public void markCompleted() {
        completed = true;
    }
}
