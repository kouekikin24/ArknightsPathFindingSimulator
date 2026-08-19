/**
 * Shared non-negative/consecutive global-frame sequencing guard used by both
 * Stage and PathfindingSimulator. The sentinel Long.MIN_VALUE marks "no frame
 * seen yet" so the first accepted frame may be any non-negative value.
 */
final class GlobalFrameSequencer {
    private GlobalFrameSequencer() {
    }

    static void requireNext(long lastGlobalFrame, long globalFrame) {
        if (globalFrame < 0L) {
            throw new IllegalArgumentException("Global frame must be non-negative");
        }
        if (lastGlobalFrame != Long.MIN_VALUE && globalFrame != lastGlobalFrame + 1L) {
            throw new IllegalArgumentException("Global frames must be consecutive: expected "
                    + (lastGlobalFrame + 1L) + ", got " + globalFrame);
        }
    }
}
