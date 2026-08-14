/** Immutable editor-grid coordinate using the simulator's left-top origin. */
public record UiCell(int x, int y) {
    public UiPoint center() {
        return new UiPoint(x + 0.5f, y + 0.5f);
    }
}
