/** UI-side checkpoint kinds with display labels; the matching core type is carried alongside. */
public enum UiCheckpointType {
    MOVE("移动", true, CheckpointType.MOVE),
    PATROL_MOVE("巡逻", true, CheckpointType.PATROL_MOVE),
    APPEAR_AT_POS("传送出现", true, CheckpointType.APPEAR_AT_POS),
    WAIT_FOR_SECONDS("等待秒数", false, CheckpointType.WAIT_FOR_SECONDS),
    WAIT_FOR_PLAY_TIME("等待总时长", false, CheckpointType.WAIT_FOR_PLAY_TIME),
    WAIT_CURRENT_FRAGMENT_TIME("等待当前节", false, CheckpointType.WAIT_CURRENT_FRAGMENT_TIME),
    WAIT_CURRENT_WAVE_TIME("等待当前波", false, CheckpointType.WAIT_CURRENT_WAVE_TIME),
    WAIT_BOSSRUSH_WAVE("等待区块", false, CheckpointType.WAIT_BOSSRUSH_WAVE),
    DISAPPEAR("消失", false, CheckpointType.DISAPPEAR),
    ALERT("警报", false, CheckpointType.ALERT);

    private final String label;
    private final boolean hasPoint;
    private final CheckpointType core;

    UiCheckpointType(String label, boolean hasPoint, CheckpointType core) {
        this.label = label;
        this.hasPoint = hasPoint;
        this.core = core;
    }

    public String label() {
        return label;
    }

    /** Whether checkpoints of this kind are placed on a map cell. */
    public boolean hasPoint() {
        return hasPoint;
    }

    /** Whether the seconds parameter applies to this kind. */
    public boolean usesSeconds() {
        return !hasPoint && (core == CheckpointType.WAIT_FOR_SECONDS
                || core == CheckpointType.WAIT_FOR_PLAY_TIME
                || core == CheckpointType.WAIT_CURRENT_FRAGMENT_TIME
                || core == CheckpointType.WAIT_CURRENT_WAVE_TIME);
    }

    /** Whether the boss-rush area parameter applies to this kind. */
    public boolean usesArea() {
        return core == CheckpointType.WAIT_BOSSRUSH_WAVE;
    }

    public CheckpointType core() {
        return core;
    }

    /**
     * The single type-to-construction mapping: every editor/codec construction
     * of a checkpoint goes through here so a new type is added in one place.
     * The point is required iff hasPoint(); seconds/area are ignored unless the
     * type uses them.
     */
    public UiCheckpoint create(UiPoint point, float seconds, int area) {
        return switch (this) {
            case MOVE -> UiCheckpoint.move(point);
            case PATROL_MOVE -> UiCheckpoint.patrolMove(point);
            case APPEAR_AT_POS -> UiCheckpoint.appearAt(point);
            case WAIT_FOR_SECONDS -> UiCheckpoint.waitForSeconds(seconds);
            case WAIT_FOR_PLAY_TIME -> UiCheckpoint.waitForPlayTime(seconds);
            case WAIT_CURRENT_FRAGMENT_TIME -> UiCheckpoint.waitForFragmentTime(seconds);
            case WAIT_CURRENT_WAVE_TIME -> UiCheckpoint.waitForWaveTime(seconds);
            case WAIT_BOSSRUSH_WAVE -> UiCheckpoint.waitForBossRushArea(area);
            case DISAPPEAR -> UiCheckpoint.disappear();
            case ALERT -> UiCheckpoint.alert();
        };
    }

    public UiCheckpoint create(UiPoint point) {
        return create(point, 0f, 0);
    }
}
