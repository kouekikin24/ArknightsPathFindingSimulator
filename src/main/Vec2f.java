/** Logical map-space vector. X grows right; Y grows down. */
public record Vec2f(float x, float y) {
    public static final Vec2f ZERO = new Vec2f(0f, 0f);
    private static final float UNITY_NORMALIZATION_EPSILON = 0.00001f;

    public Vec2f add(Vec2f other) {
        return new Vec2f(x + other.x, y + other.y);
    }

    /** Non-null with both components finite; the shared record-validation predicate. */
    public static boolean isFinite(Vec2f value) {
        return value != null && Float.isFinite(value.x()) && Float.isFinite(value.y());
    }

    public Vec2f subtract(Vec2f other) {
        return new Vec2f(x - other.x, y - other.y);
    }

    public Vec2f multiply(float scalar) {
        return new Vec2f(x * scalar, y * scalar);
    }

    public Vec2f multiplyComponents(Vec2f other) {
        return new Vec2f(x * other.x, y * other.y);
    }

    public Vec2f abs() {
        return new Vec2f(F32.abs(x), F32.abs(y));
    }

    public Vec2f max(Vec2f other) {
        return new Vec2f(F32.max(x, other.x), F32.max(y, other.y));
    }

    public float dot(Vec2f other) {
        return x * other.x + y * other.y;
    }

    public float lengthSquared() {
        return dot(this);
    }

    public float length() {
        return F32.sqrt(lengthSquared());
    }

    public Vec2f normalized() {
        float length = length();
        // Match Unity Vector2.normalized: test magnitude first, then divide each component.
        return length > UNITY_NORMALIZATION_EPSILON
                ? new Vec2f(x / length, y / length)
                : ZERO;
    }

    public Vec2f clampMagnitude(float maximum) {
        float maximumSquared = maximum * maximum;
        if (lengthSquared() <= maximumSquared) {
            return this;
        }
        float magnitude = length();
        // Unity Vector2.ClampMagnitude divides the stored components directly
        // after its squared-magnitude comparison.
        return new Vec2f(x / magnitude * maximum, y / magnitude * maximum);
    }

    public Vec2f projectOnto(Vec2f direction) {
        float denominator = direction.lengthSquared();
        return denominator <= F32.EPSILON_SQUARED ? ZERO : direction.multiply(dot(direction) / denominator);
    }

    public float distanceTo(Vec2f other) {
        return subtract(other).length();
    }
}
