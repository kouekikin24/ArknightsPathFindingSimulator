/** Logical map-space vector. X grows right; Y grows down. */
public record Vec2f(float x, float y) {
    public static final Vec2f ZERO = new Vec2f(0f, 0f);

    public Vec2f add(Vec2f other) {
        return new Vec2f(x + other.x, y + other.y);
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
        return length <= F32.EPSILON ? ZERO : multiply(1f / length);
    }

    public Vec2f clampMagnitude(float maximum) {
        float length = length();
        return length > maximum && length > F32.EPSILON ? multiply(maximum / length) : this;
    }

    public Vec2f projectOnto(Vec2f direction) {
        float denominator = direction.lengthSquared();
        return denominator <= F32.EPSILON ? ZERO : direction.multiply(dot(direction) / denominator);
    }

    public float distanceTo(Vec2f other) {
        return subtract(other).length();
    }
}
