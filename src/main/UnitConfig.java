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
        if (!Float.isFinite(attributeSpeed) || !Float.isFinite(moveMultiplier)
                || !Float.isFinite(steeringFactor) || !Float.isFinite(maxSteeringForce)
                || !Float.isFinite(halfBodyWidth)) {
            throw new IllegalArgumentException("Movement scalars must be finite");
        }
        if (attributeSpeed < 0f || moveMultiplier < 0f || steeringFactor < 0f
                || maxSteeringForce < 0f || halfBodyWidth < 0f) {
            throw new IllegalArgumentException("Movement scalars cannot be negative");
        }
        if (!finite(spawnEntityOffset) || !finite(footOffset)) {
            throw new IllegalArgumentException("Movement offsets must be finite");
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

    public UnitConfig withVisitEveryNodeStably(boolean value) {
        return new UnitConfig(attributeSpeed, moveMultiplier, steeringFactor, maxSteeringForce,
                spawnEntityOffset, footOffset, halfBodyWidth,
                visitEveryTileCenter, visitEveryNodeCenter, value);
    }

    public UnitConfig withVisitEveryTileCenter(boolean value) {
        return new UnitConfig(attributeSpeed, moveMultiplier, steeringFactor, maxSteeringForce,
                spawnEntityOffset, footOffset, halfBodyWidth,
                value, visitEveryNodeCenter, visitEveryNodeStably);
    }

    public UnitConfig withVisitEveryNodeCenter(boolean value) {
        return new UnitConfig(attributeSpeed, moveMultiplier, steeringFactor, maxSteeringForce,
                spawnEntityOffset, footOffset, halfBodyWidth,
                visitEveryTileCenter, value, visitEveryNodeStably);
    }

    public float theoreticalSpeed() {
        return F32.max(attributeSpeed, 0.1f) * moveMultiplier;
    }

    private static boolean finite(Vec2f value) {
        return value != null && Float.isFinite(value.x()) && Float.isFinite(value.y());
    }
}
