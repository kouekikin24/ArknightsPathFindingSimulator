/** UI-level movement choice. SimulationSession converts it to the core enum. */
public enum UiMovementMode {
    GROUND("地面"),
    FLYING("飞行");

    private final String displayName;

    UiMovementMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
