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
        return checkpointIndex < route.checkpoints().size() ? route.checkpoints().get(checkpointIndex) : null;
    }

    public void enterAt(StageClock clock) {
        enteredPlayTime = clock.playTime();
    }

    /**
     * Patrol routes return to the first checkpoint after a terminal patrol
     * move whose target differs from the first target. All other routes
     * advance toward the endpoint.
     */
    public void advance(Route route, StageClock clock) {
        Checkpoint current = current(route);
        if (current != null && current.type() == CheckpointType.PATROL_MOVE
                && checkpointIndex == route.checkpoints().size() - 1
                && !current.point().equals(route.checkpoints().getFirst().point())) {
            checkpointIndex = 0;
        } else {
            checkpointIndex++;
        }
        enteredPlayTime = clock.playTime();
    }

    public void markCompleted() {
        completed = true;
    }
}
