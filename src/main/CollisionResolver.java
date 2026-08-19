/**
 * Sweeps a requested displacement through every crossed tile boundary.
 *
 * <p>A collision removes the complete frame component normal to the blocked
 * boundary from both the displacement and the inertia velocity. The remaining
 * tangential displacement is then swept again from the original position.
 * This deliberately does not advance a unit to the wall before it slides.</p>
 */
public final class CollisionResolver {
    private static final int MAXIMUM_SWEPT_TILES = 4096;

    public CollisionResult resolve(GridMap map, MovementMode mode, UnitState unit,
                                   Vec2f requestedDisplacement, Vec2f nextVelocity) {
        return resolve(map, mode, unit, requestedDisplacement, nextVelocity, true);
    }

    /**
     * Resolves a displacement, optionally treating the stage boundary as a wall.
     * While a unit is still outside the map, the boundary must be open so it can
     * enter; in-map terrain remains collision blocking in either mode.
     */
    public CollisionResult resolve(GridMap map, MovementMode mode, UnitState unit,
                                   Vec2f requestedDisplacement, Vec2f nextVelocity,
                                   boolean stageBoundaryBlocks) {
        requireFinite(requestedDisplacement, "Requested displacement");
        requireFinite(nextVelocity, "Next velocity");
        requireFinite(unit.entityPosition(), "Entity position");
        Vec2f correctedDisplacement = requestedDisplacement;
        Vec2f correctedVelocity = nextVelocity;
        boolean collided = false;

        // There are only two independently removable normal components. Restart
        // each sweep at the source so a retained tangent is checked for the full
        // frame rather than only for the post-impact remainder.
        for (int correction = 0; correction < 2; correction++) {
            CollisionAxes hit = firstCollision(map, mode, unit.entityPosition(),
                    correctedDisplacement, stageBoundaryBlocks);
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
                                         Vec2f position, Vec2f displacement,
                                         boolean stageBoundaryBlocks) {
        TileCoord current = TileCoord.fromPosition(position);
        Vec2f destination = position.add(displacement);
        TileCoord destinationTile = TileCoord.fromPosition(destination);
        int stepX = direction(displacement.x());
        int stepY = direction(displacement.y());
        requireSweepInRange(current, destinationTile);

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

                boolean blockX = blocked(map, mode, xNeighbor, stageBoundaryBlocks);
                boolean blockY = blocked(map, mode, yNeighbor, stageBoundaryBlocks);
                // A corner entry is not legal unless all three adjacent cells are
                // legal. A blocked diagonal removes both normal components.
                if (blocked(map, mode, diagonalNeighbor, stageBoundaryBlocks)) {
                    return CollisionAxes.BOTH;
                }
                if (blockX || blockY) {
                    return new CollisionAxes(blockX, blockY);
                }
                current = diagonalNeighbor;
            } else if (!crossesY || (crossesX && timeX < timeY)) {
                TileCoord xNeighbor = new TileCoord(current.x() + stepX, current.y());
                if (blocked(map, mode, xNeighbor, stageBoundaryBlocks)) {
                    return CollisionAxes.X;
                }
                current = xNeighbor;
            } else {
                TileCoord yNeighbor = new TileCoord(current.x(), current.y() + stepY);
                if (blocked(map, mode, yNeighbor, stageBoundaryBlocks)) {
                    return CollisionAxes.Y;
                }
                current = yNeighbor;
            }
        }
        return null;
    }

    private boolean blocked(GridMap map, MovementMode mode, TileCoord tile,
                            boolean stageBoundaryBlocks) {
        return !map.contains(tile) ? stageBoundaryBlocks : map.collisionBlocked(tile, mode);
    }

    private boolean hasPendingCrossing(int current, int destination, int step) {
        return step != 0 && current != destination;
    }

    private int direction(float value) {
        // Treat both signed zeroes and float noise as stationary: a denormal
        // displacement would otherwise sweep an axis whose crossing time is
        // astronomical, and Inf == Inf could masquerade as a diagonal tie.
        if (F32.abs(value) <= F32.EPSILON) {
            return 0;
        }
        return value < 0f ? -1 : 1;
    }

    private float crossingTime(float position, float displacement, int currentTile, int step) {
        float boundary = step > 0 ? currentTile + 1f : currentTile;
        return (boundary - position) / displacement;
    }

    private static void requireFinite(Vec2f value, String name) {
        if (!Float.isFinite(value.x()) || !Float.isFinite(value.y())) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
    }

    private static void requireSweepInRange(TileCoord from, TileCoord to) {
        long span = Math.abs((long) to.x() - from.x()) + Math.abs((long) to.y() - from.y());
        if (span > MAXIMUM_SWEPT_TILES) {
            throw new IllegalArgumentException("A single frame cannot sweep " + span
                    + " tiles; check the configured speed");
        }
    }

    private record CollisionAxes(boolean blockX, boolean blockY) {
        private static final CollisionAxes X = new CollisionAxes(true, false);
        private static final CollisionAxes Y = new CollisionAxes(false, true);
        private static final CollisionAxes BOTH = new CollisionAxes(true, true);
    }
}
