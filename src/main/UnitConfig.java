/** Per-unit steering and coordinate configuration. */
public record UnitConfig(
        float attributeSpeed,
        float moveMultiplier,
        float steeringFactor,
        float maxSteeringForce,
        Vec2f spawnEntityOffset,
        Vec2f footOffset,
        float halfBodyWidth,
        boolean visitEveryTileCenter,
        boolean visitEveryNodeCenter,
        boolean visitEveryNodeStably) {

    public UnitConfig {
        if (moveMultiplier < 0f || steeringFactor < 0f || maxSteeringForce < 0f || halfBodyWidth < 0f) {
            throw new IllegalArgumentException("Movement scalars cannot be negative");
        }
    }

    public static UnitConfig normalGround(float attributeSpeed) {
        return new UnitConfig(attributeSpeed, 0.5f, 8f, 10f,
                Vec2f.ZERO, new Vec2f(0f, -0.2f), 0.2f,
                false, false, false);
    }

    public static UnitConfig normalFlying(float attributeSpeed) {
        return new UnitConfig(attributeSpeed, 0.5f, 20f, 100f,
                Vec2f.ZERO, new Vec2f(0f, 0f), 0.2f,
                false, false, false);
    }

    public float theoreticalSpeed() {
        return F32.max(attributeSpeed, 0.1f) * moveMultiplier;
    }
}
