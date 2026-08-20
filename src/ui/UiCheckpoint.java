import java.util.Objects;

/** Editor-side checkpoint value: a type plus its map cell, seconds, or area argument. */
public record UiCheckpoint(UiCheckpointType type, UiCell cell, float value, int area) {

    public UiCheckpoint {
        Objects.requireNonNull(type, "Checkpoint type is required");
        if (type.hasPoint() != (cell != null)) {
            throw new IllegalArgumentException(type.label()
                    + (type.hasPoint() ? " 需要地图坐标" : " 不接受地图坐标"));
        }
        if (!Float.isFinite(value) || value < 0f) {
            throw new IllegalArgumentException("检查点数值必须有限且非负");
        }
        if (area < 0) {
            throw new IllegalArgumentException("检查点区块必须非负");
        }
        if (value != 0f && !type.usesSeconds()) {
            throw new IllegalArgumentException(type.label() + " 不接受秒数参数");
        }
        if (area != 0 && !type.usesArea()) {
            throw new IllegalArgumentException(type.label() + " 不接受区块参数");
        }
    }

    public static UiCheckpoint move(UiCell cell) {
        return new UiCheckpoint(UiCheckpointType.MOVE, cell, 0f, 0);
    }

    public static UiCheckpoint patrolMove(UiCell cell) {
        return new UiCheckpoint(UiCheckpointType.PATROL_MOVE, cell, 0f, 0);
    }

    public static UiCheckpoint appearAt(UiCell cell) {
        return new UiCheckpoint(UiCheckpointType.APPEAR_AT_POS, cell, 0f, 0);
    }

    public static UiCheckpoint waitForSeconds(float seconds) {
        return new UiCheckpoint(UiCheckpointType.WAIT_FOR_SECONDS, null, seconds, 0);
    }

    public static UiCheckpoint waitForPlayTime(float seconds) {
        return new UiCheckpoint(UiCheckpointType.WAIT_FOR_PLAY_TIME, null, seconds, 0);
    }

    public static UiCheckpoint waitForFragmentTime(float seconds) {
        return new UiCheckpoint(UiCheckpointType.WAIT_CURRENT_FRAGMENT_TIME, null, seconds, 0);
    }

    public static UiCheckpoint waitForWaveTime(float seconds) {
        return new UiCheckpoint(UiCheckpointType.WAIT_CURRENT_WAVE_TIME, null, seconds, 0);
    }

    public static UiCheckpoint waitForBossRushArea(int area) {
        return new UiCheckpoint(UiCheckpointType.WAIT_BOSSRUSH_WAVE, null, 0f, area);
    }

    public static UiCheckpoint disappear() {
        return new UiCheckpoint(UiCheckpointType.DISAPPEAR, null, 0f, 0);
    }

    public static UiCheckpoint alert() {
        return new UiCheckpoint(UiCheckpointType.ALERT, null, 0f, 0);
    }
}
