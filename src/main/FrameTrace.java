/** A complete enough per-frame audit record to compare against game recordings. */
public record FrameTrace(
        int frame,
        int checkpointIndex,
        CheckpointType checkpointType,
        UnitMode modeBefore,
        UnitMode modeAfter,
        Vec2f entityBefore,
        Vec2f entityAfter,
        Vec2f cursorBefore,
        Vec2f cursorAfter,
        TileCoord cursorTile,
        TileCoord nextNode,
        Vec2f target,
        Vec2f givenDirection,
        boolean avoidanceRecomputed,
        Vec2f avoidance,
        Vec2f inertiaBefore,
        Vec2f inertiaAfter,
        Vec2f requestedDisplacement,
        Vec2f appliedDisplacement,
        String transition) {
}
