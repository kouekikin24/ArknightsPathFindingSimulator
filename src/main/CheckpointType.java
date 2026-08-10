public enum CheckpointType {
    MOVE,
    WAIT_FOR_SECONDS,
    WAIT_FOR_PLAY_TIME,
    WAIT_CURRENT_FRAGMENT_TIME,
    WAIT_CURRENT_WAVE_TIME,
    DISAPPEAR,
    APPEAR_AT_POS,
    ALERT,
    PATROL_MOVE,
    WAIT_BOSSRUSH_WAVE;

    public boolean isMovement() {
        return this == MOVE || this == PATROL_MOVE;
    }

    public boolean isImmediate() {
        return this == DISAPPEAR || this == APPEAR_AT_POS || this == ALERT;
    }
}
