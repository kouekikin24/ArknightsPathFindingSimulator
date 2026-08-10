/** Float32 steering and inertia integration for one normal movement frame. */
public final class MotionIntegrator {
    public MotionResult integrate(UnitConfig config, UnitState unit, Vec2f givenDirection) {
        float theoreticalSpeed = config.theoreticalSpeed();
        Vec2f inertia = unit.inertiaVelocity();
        float avoidanceScale = theoreticalSpeed <= F32.EPSILON
                ? 0.5f
                : F32.max(inertia.length() / theoreticalSpeed, 0.5f);
        Vec2f actualAvoidance = unit.cachedAvoidance().multiply(avoidanceScale);
        Vec2f desiredVelocity = givenDirection.multiply(theoreticalSpeed);
        Vec2f acceleration = desiredVelocity.subtract(inertia)
                .multiply(config.steeringFactor())
                .add(actualAvoidance)
                .clampMagnitude(config.maxSteeringForce());
        Vec2f nextVelocity = inertia.add(acceleration.multiply(F32.DT)).clampMagnitude(theoreticalSpeed);
        Vec2f requestedDisplacement = nextVelocity.multiply(F32.DT);
        return new MotionResult(acceleration, nextVelocity, requestedDisplacement);
    }

    public record MotionResult(Vec2f acceleration, Vec2f velocity, Vec2f requestedDisplacement) {
    }
}
