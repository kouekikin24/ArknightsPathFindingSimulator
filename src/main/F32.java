/** Float-only helpers. Keep vector operations here so Java doubles do not leak into simulation state. */
public final class F32 {
    public static final float DT = 1f / 30f;
    public static final float EPSILON = 0.000001f;
    public static final float EPSILON_SQUARED = EPSILON * EPSILON;

    private F32() {
    }

    public static float sqrt(float value) {
        return (float) Math.sqrt(value);
    }

    public static float abs(float value) {
        return Math.abs(value);
    }

    public static float max(float left, float right) {
        return Math.max(left, right);
    }

    public static float min(float left, float right) {
        return Math.min(left, right);
    }

    public static float clamp(float value, float min, float max) {
        return max(min, min(value, max));
    }

}
