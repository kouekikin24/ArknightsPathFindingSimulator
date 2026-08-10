/**
 * Compact data form for every checkpoint in the research table. Fields that
 * do not apply to a type are ignored: point for MOVE/APPEAR, value for waits,
 * and area for WAIT_BOSSRUSH_WAVE.
 */
public record Checkpoint(
        CheckpointType type,
        Vec2f point,
        float radius,
        float value,
        int area) {

    public Checkpoint {
        if (type.isMovement() || type == CheckpointType.APPEAR_AT_POS) {
            if (point == null) {
                throw new IllegalArgumentException(type + " requires a point");
            }
        }
    }

    public static Checkpoint move(Vec2f point) {
        return new Checkpoint(CheckpointType.MOVE, point, 0.05f, 0f, 0);
    }

    public static Checkpoint move(Vec2f point, float radius) {
        return new Checkpoint(CheckpointType.MOVE, point, radius, 0f, 0);
    }

    public static Checkpoint patrolMove(Vec2f point) {
        return new Checkpoint(CheckpointType.PATROL_MOVE, point, 0.05f, 0f, 0);
    }

    public static Checkpoint waitForSeconds(float seconds) {
        return new Checkpoint(CheckpointType.WAIT_FOR_SECONDS, null, 0f, seconds, 0);
    }

    public static Checkpoint waitForPlayTime(float seconds) {
        return new Checkpoint(CheckpointType.WAIT_FOR_PLAY_TIME, null, 0f, seconds, 0);
    }

    public static Checkpoint waitForFragmentTime(float seconds) {
        return new Checkpoint(CheckpointType.WAIT_CURRENT_FRAGMENT_TIME, null, 0f, seconds, 0);
    }

    public static Checkpoint waitForWaveTime(float seconds) {
        return new Checkpoint(CheckpointType.WAIT_CURRENT_WAVE_TIME, null, 0f, seconds, 0);
    }

    public static Checkpoint disappear() {
        return new Checkpoint(CheckpointType.DISAPPEAR, null, 0f, 0f, 0);
    }

    public static Checkpoint appearAt(Vec2f point) {
        return new Checkpoint(CheckpointType.APPEAR_AT_POS, point, 0f, 0f, 0);
    }

    public static Checkpoint alert() {
        return new Checkpoint(CheckpointType.ALERT, null, 0f, 0f, 0);
    }

    public static Checkpoint waitForBossRushArea(int area) {
        return new Checkpoint(CheckpointType.WAIT_BOSSRUSH_WAVE, null, 0f, 0f, area);
    }
}
