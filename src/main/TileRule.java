/**
 * A terrain tile keeps routing, smoothing, avoidance, and collision concerns
 * separate. A high-cost obstacle is intentionally not the same thing as an
 * unenterable tile.
 */
public record TileRule(
        boolean groundPassable,
        boolean flyingPassable,
        float groundEntryCost,
        float flyingEntryCost,
        boolean groundSmoothingBlocked,
        boolean flyingSmoothingBlocked,
        boolean groundAvoidanceBlocked,
        boolean flyingAvoidanceBlocked,
        boolean groundCollisionBlocked,
        boolean flyingCollisionBlocked) {

    public static final float NORMAL_COST = 1f;
    public static final float BOX_COST = 1_000f;
    public static final float PIT_COST = 1_000_000f;

    public TileRule {
        requirePositiveCost(groundEntryCost, "groundEntryCost");
        requirePositiveCost(flyingEntryCost, "flyingEntryCost");
    }

    public static TileRule open() {
        return new TileRule(true, true, NORMAL_COST, NORMAL_COST,
                false, false, false, false, false, false);
    }

    /** A costly obstacle is route- and avoidance-blocking but not necessarily a hard collision tile. */
    public static TileRule costlyObstacle(float groundCost) {
        return new TileRule(true, true, groundCost, NORMAL_COST,
                true, false, true, false, false, false);
    }

    public static TileRule box() {
        return costlyObstacle(BOX_COST);
    }

    /** A pit is costly ground, not ground-impassable terrain. */
    public static TileRule pit() {
        return costlyObstacle(PIT_COST);
    }

    public static TileRule impassable() {
        return new TileRule(false, false, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                true, true, true, true, true, true);
    }

    public boolean passable(MovementMode mode) {
        return mode == MovementMode.GROUND ? groundPassable : flyingPassable;
    }

    public float entryCost(MovementMode mode) {
        return mode == MovementMode.GROUND ? groundEntryCost : flyingEntryCost;
    }

    public boolean smoothingBlocked(MovementMode mode) {
        return mode == MovementMode.GROUND ? groundSmoothingBlocked : flyingSmoothingBlocked;
    }

    public boolean avoidanceBlocked(MovementMode mode) {
        return mode == MovementMode.GROUND ? groundAvoidanceBlocked : flyingAvoidanceBlocked;
    }

    public boolean collisionBlocked(MovementMode mode) {
        return mode == MovementMode.GROUND ? groundCollisionBlocked : flyingCollisionBlocked;
    }

    private static void requirePositiveCost(float value, String name) {
        if (Float.isNaN(value) || value <= 0f) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
