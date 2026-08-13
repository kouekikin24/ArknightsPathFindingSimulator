/**
 * Sweeps a requested displacement through every crossed tile boundary.
 *
 * <p>A collision removes the complete frame component normal to the blocked
 * boundary from both the displacement and the inertia velocity. The remaining
 * tangential displacement is then swept again from the original position.
 * This deliberately does not advance a unit to the wall before it slides.</p>
 */
public final class CollisionResolver {
    public CollisionResult resolve(GridMap map, MovementMode mode, UnitState unit,
                                   Vec2f requestedDisplacement, Vec2f nextVelocity) {
        Vec2f correctedDisplacement = requestedDisplacement;
        Vec2f correctedVelocity = nextVelocity;
        boolean collided = false;

        // There are only two independently removable normal components. Restart
        // each sweep at the source so a retained tangent is checked for the full
        // frame rather than only for the post-impact remainder.
        for (int correction = 0; correction < 2; correction++) {
            CollisionAxes hit = firstCollision(map, mode, unit.entityPosition(), correctedDisplacement);
            if (hit == null) {
                break;
            }

            boolean removeX = hit.blockX() && correctedDisplacement.x() != 0f;
            boolean removeY = hit.blockY() && correctedDisplacement.y() != 0f;
            if (!removeX && !removeY) {
                // Defensive exit for a malformed non-finite displacement. Normal
                // simulation inputs are finite and always remove an axis here.
                break;
            }

            collided = true;
            if (removeX) {
                correctedDisplacement = new Vec2f(0f, correctedDisplacement.y());
                correctedVelocity = new Vec2f(0f, correctedVelocity.y());
            }
            if (removeY) {
                correctedDisplacement = new Vec2f(correctedDisplacement.x(), 0f);
                correctedVelocity = new Vec2f(correctedVelocity.x(), 0f);
            }
        }
        return new CollisionResult(correctedDisplacement, correctedVelocity, collided);
    }

    /**
     * Finds the first blocked boundary on one monotonic segment. Open cells are
     * traversed in DDA order so a wall several tiles away is still observed.
     */
    private CollisionAxes firstCollision(GridMap map, MovementMode mode,
                                         Vec2f position, Vec2f displacement) {
        TileCoord current = TileCoord.fromPosition(position);
        Vec2f destination = position.add(displacement);
        TileCoord destinationTile = TileCoord.fromPosition(destination);
        int stepX = direction(displacement.x());
        int stepY = direction(displacement.y());

        while (hasPendingCrossing(current.x(), destinationTile.x(), stepX)
                || hasPendingCrossing(current.y(), destinationTile.y(), stepY)) {
            boolean crossesX = hasPendingCrossing(current.x(), destinationTile.x(), stepX);
            boolean crossesY = hasPendingCrossing(current.y(), destinationTile.y(), stepY);
            float timeX = crossesX
                    ? crossingTime(position.x(), displacement.x(), current.x(), stepX)
                    : Float.POSITIVE_INFINITY;
            float timeY = crossesY
                    ? crossingTime(position.y(), displacement.y(), current.y(), stepY)
                    : Float.POSITIVE_INFINITY;

            if (crossesX && crossesY && timeX == timeY) {
                TileCoord xNeighbor = new TileCoord(current.x() + stepX, current.y());
                TileCoord yNeighbor = new TileCoord(current.x(), current.y() + stepY);
                TileCoord diagonalNeighbor = new TileCoord(current.x() + stepX, current.y() + stepY);

                boolean blockX = map.collisionBlocked(xNeighbor, mode);
                boolean blockY = map.collisionBlocked(yNeighbor, mode);
                // A corner entry is not legal unless all three adjacent cells are
                // legal. A blocked diagonal removes both normal components.
                boolean blockDiagonal = map.collisionBlocked(diagonalNeighbor, mode);
                if (blockDiagonal) {
                    return CollisionAxes.BOTH;
                }
                if (blockX || blockY) {
                    return new CollisionAxes(blockX, blockY);
                }
                current = diagonalNeighbor;
            } else if (!crossesY || (crossesX && timeX < timeY)) {
                TileCoord xNeighbor = new TileCoord(current.x() + stepX, current.y());
                if (map.collisionBlocked(xNeighbor, mode)) {
                    return CollisionAxes.X;
                }
                current = xNeighbor;
            } else {
                TileCoord yNeighbor = new TileCoord(current.x(), current.y() + stepY);
                if (map.collisionBlocked(yNeighbor, mode)) {
                    return CollisionAxes.Y;
                }
                current = yNeighbor;
            }
        }
        return null;
    }

    private boolean hasPendingCrossing(int current, int destination, int step) {
        return step != 0 && current != destination;
    }

    private int direction(float value) {
        // Treat both signed zeroes as stationary. Float.compare avoids narrowing
        // the float to an integer before choosing the DDA direction.
        if (value == 0f) {
            return 0;
        }
        return Float.compare(value, 0f) < 0 ? -1 : 1;
    }

    private float crossingTime(float position, float displacement, int currentTile, int step) {
        float boundary = step > 0 ? currentTile + 1f : currentTile;
        return (boundary - position) / displacement;
    }

    private record CollisionAxes(boolean blockX, boolean blockY) {
        private static final CollisionAxes X = new CollisionAxes(true, false);
        private static final CollisionAxes Y = new CollisionAxes(false, true);
        private static final CollisionAxes BOTH = new CollisionAxes(true, true);
    }
}
