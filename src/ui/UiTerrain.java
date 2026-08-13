/** Terrain choices exposed by the editor without leaking core tile rules to Swing widgets. */
public enum UiTerrain {
    OPEN("通路"),
    BOX("箱子"),
    PIT("坑"),
    WALL("墙");

    private final String displayName;

    UiTerrain(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
