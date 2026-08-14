/** Immutable float32 point published by the UI adapter. */
public record UiPoint(float x, float y) {
    public static final UiPoint ZERO = new UiPoint(0f, 0f);
}
