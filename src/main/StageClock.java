/** External stage clock values consumed by time- and area-based checkpoints. */
public final class StageClock {
    private long frame;
    private long fragmentBaseFrame;
    private long waveBaseFrame;
    private float playTimeBias;
    private int bossRushArea;

    public long frame() {
        return frame;
    }

    public float playTime() {
        return playTimeBias + elapsedSeconds(frame);
    }

    public float fragmentTime() {
        return elapsedSeconds(frame - fragmentBaseFrame);
    }

    public float waveTime() {
        return elapsedSeconds(frame - waveBaseFrame);
    }

    public int bossRushArea() {
        return bossRushArea;
    }

    public void tick() {
        frame++;
    }

    public void setPlayTime(float playTime) {
        if (!Float.isFinite(playTime)) {
            throw new IllegalArgumentException("Play time must be finite");
        }
        playTimeBias = playTime - elapsedSeconds(frame);
    }

    public void resetFragmentTime() {
        fragmentBaseFrame = frame;
    }

    public void resetWaveTime() {
        waveBaseFrame = frame;
    }

    public void setBossRushArea(int bossRushArea) {
        if (bossRushArea < 0) {
            throw new IllegalArgumentException("Boss rush area must be non-negative");
        }
        this.bossRushArea = bossRushArea;
    }

    private static float elapsedSeconds(long frames) {
        // Widen before multiplying: frames * F32.DT would promote the long to
        // float first, silently quantizing every frame count past 2^24. The
        // double product stays integral-exact for any feasible frame count and
        // narrows to the same float32 tick grid deterministically.
        return (float) frames * F32.DT;
    }
}
