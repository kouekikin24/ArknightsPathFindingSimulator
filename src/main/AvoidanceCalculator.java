/** Implements the documented three-frame obstacle-avoidance calculation. */
public final class AvoidanceCalculator {
    private static final TileCoord[] NEAREST_PASSABLE_TIE_ORDER = {
            new TileCoord(0, -1), new TileCoord(1, -1), new TileCoord(1, 0), new TileCoord(1, 1),
            new TileCoord(0, 1), new TileCoord(-1, 1), new TileCoord(-1, 0), new TileCoord(-1, -1)
    };

    public Vec2f calculate(GridMap map, MovementMode mode, UnitConfig config,
                           UnitState unit, Vec2f givenDirection) {
        TileCoord current = TileCoord.fromPosition(unit.cursorPosition());
        if (!map.contains(current)) {
            return Vec2f.ZERO;
        }

        Vec2f intermediate = map.passable(current, mode)
                ? fromSurroundingTiles(map, mode, config, unit, current)
                : towardNearestPassable(map, mode, current);

        return intermediate.subtract(intermediate.projectOnto(givenDirection));
    }

    private Vec2f towardNearestPassable(GridMap map, MovementMode mode, TileCoord current) {
        TileCoord best = null;
        float bestDistanceSquared = Float.POSITIVE_INFINITY;
        for (TileCoord offset : NEAREST_PASSABLE_TIE_ORDER) {
            TileCoord candidate = current.add(offset);
            if (!map.passable(candidate, mode)) {
                continue;
            }
            float distanceSquared = offset.x() * offset.x() + offset.y() * offset.y();
            if (distanceSquared < bestDistanceSquared) {
                best = candidate;
                bestDistanceSquared = distanceSquared;
            }
        }
        return best == null ? Vec2f.ZERO : best.center().subtract(current.center()).normalized();
    }

    private Vec2f fromSurroundingTiles(GridMap map, MovementMode mode, UnitConfig config,
                                       UnitState unit, TileCoord current) {
        Vec2f center = current.center();
        Vec2f foot = unit.footPosition(config);
        Vec2f sum = Vec2f.ZERO;

        for (int relativeY = -1; relativeY <= 1; relativeY++) {
            for (int relativeX = -1; relativeX <= 1; relativeX++) {
                if (relativeX == 0 && relativeY == 0) {
                    continue;
                }
                TileCoord neighbor = new TileCoord(current.x() + relativeX, current.y() + relativeY);
                if (!map.avoidanceBlocked(neighbor, mode)) {
                    continue;
                }

                float nearestX = foot.x() + Integer.compare(relativeX, 0) * config.halfBodyWidth();
                Vec2f nearestPoint = new Vec2f(nearestX, foot.y());
                Vec2f relative = new Vec2f(relativeX, relativeY);
                Vec2f positiveTowardObstacle = nearestPoint.subtract(center)
                        .multiplyComponents(relative)
                        .max(Vec2f.ZERO);
                Vec2f effectiveOffset = positiveTowardObstacle
                        .subtract(new Vec2f(0.25f, 0.25f))
                        .multiplyComponents(relative.abs());

                boolean cardinal = relativeX == 0 || relativeY == 0;
                if (cardinal) {
                    if (effectiveOffset.x() > 0f || effectiveOffset.y() > 0f) {
                        sum = sum.add(effectiveOffset.multiply(-1f).multiplyComponents(relative));
                    }
                } else if (effectiveOffset.x() > 0f && effectiveOffset.y() > 0f) {
                    float average = (effectiveOffset.x() + effectiveOffset.y()) * 0.5f;
                    sum = sum.add(relative.multiply(-average));
                }
            }
        }
        // The documented 8-neighbor result is normalized before its given-direction projection.
        return sum.normalized();
    }
}
