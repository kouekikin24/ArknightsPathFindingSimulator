/** Result of one swept tile-collision correction. */
public record CollisionResult(Vec2f appliedDisplacement, Vec2f inertiaVelocity, boolean collided) {
}
