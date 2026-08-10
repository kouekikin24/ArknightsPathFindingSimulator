/** Reflects the component of a frame displacement that enters an unpassable destination tile. */
public final class CollisionResolver {
    public Vec2f resolve(GridMap map, MovementMode mode, UnitState unit, Vec2f requestedDisplacement) {
        TileCoord currentTile = TileCoord.fromPosition(unit.entityPosition());
        Vec2f destination = unit.entityPosition().add(requestedDisplacement);
        TileCoord destinationTile = TileCoord.fromPosition(destination);

        if (currentTile.equals(destinationTile) || !map.collisionBlocked(destinationTile, mode)) {
            return requestedDisplacement;
        }

        Vec2f towardBlockedTile = destinationTile.center().subtract(unit.entityPosition());
        Vec2f projected = requestedDisplacement.projectOnto(towardBlockedTile);
        return requestedDisplacement.subtract(projected.multiply(2f));
    }
}
