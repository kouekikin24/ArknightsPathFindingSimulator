/** External stage clock values consumed by time- and area-based checkpoints. */
public final class StageClock {
    private float playTime;
    private float fragmentTime;
    private float waveTime;
    private int bossRushArea;

    public float playTime() {
        return playTime;
    }

    public float fragmentTime() {
        return fragmentTime;
    }

    public float waveTime() {
        return waveTime;
    }

    public int bossRushArea() {
        return bossRushArea;
    }

    public void tick() {
        playTime += F32.DT;
        fragmentTime += F32.DT;
        waveTime += F32.DT;
    }

    public void setPlayTime(float playTime) {
        this.playTime = playTime;
    }

    public void resetFragmentTime() {
        fragmentTime = 0f;
    }

    public void resetWaveTime() {
        waveTime = 0f;
    }

    public void setBossRushArea(int bossRushArea) {
        this.bossRushArea = bossRushArea;
    }
}
