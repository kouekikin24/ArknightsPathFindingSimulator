import java.util.HashSet;
import java.util.Set;

/** Runtime state kept separate from route and unit configuration. */
public final class UnitState {
    private Vec2f entityPosition;
    private Vec2f cursorPosition;
    private Vec2f inertiaVelocity = Vec2f.ZERO;
    private Vec2f cachedAvoidance = Vec2f.ZERO;
    private UnitMode mode = UnitMode.MOVE;
    private boolean bound;
    private long lastAvoidanceFrame = Long.MIN_VALUE;
    private final RouteProgress routeProgress = new RouteProgress();
    private final Set<TileCoord> passedTileCenters = new HashSet<>();
    private TileCoord lastObservedCursorTile;
    private TileCoord previousDistinctCursorTile;
    private TileCoord visitGoalTile;
    private int visitGoalCheckpointIndex = -1;
    private int alertsShown;

    public UnitState(Route route, UnitConfig config) {
        cursorPosition = route.spawnCursorPosition();
        entityPosition = cursorPosition.add(config.spawnEntityOffset());
    }

    public Vec2f entityPosition() {
        return entityPosition;
    }

    public Vec2f cursorPosition() {
        return cursorPosition;
    }

    public Vec2f inertiaVelocity() {
        return inertiaVelocity;
    }

    public Vec2f cachedAvoidance() {
        return cachedAvoidance;
    }

    public UnitMode mode() {
        return mode;
    }

    public boolean bound() {
        return bound;
    }

    public long lastAvoidanceFrame() {
        return lastAvoidanceFrame;
    }

    public RouteProgress routeProgress() {
        return routeProgress;
    }

    public TileCoord previousDistinctCursorTile() {
        return previousDistinctCursorTile;
    }

    public Set<TileCoord> passedTileCenters() {
        return passedTileCenters;
    }

    public int alertsShown() {
        return alertsShown;
    }

    public Vec2f footPosition(UnitConfig config) {
        return entityPosition.add(config.footOffset());
    }

    public void setMode(UnitMode mode) {
        this.mode = mode;
    }

    public void setBound(boolean bound) {
        this.bound = bound;
    }

    public void setInertiaVelocity(Vec2f inertiaVelocity) {
        this.inertiaVelocity = inertiaVelocity;
    }

    public void setCachedAvoidance(Vec2f cachedAvoidance, long frame) {
        this.cachedAvoidance = cachedAvoidance;
        this.lastAvoidanceFrame = frame;
    }

    /** Translate entity and cursor together so their fixed spawn offset remains invariant. */
    public void translate(Vec2f delta) {
        entityPosition = entityPosition.add(delta);
        cursorPosition = cursorPosition.add(delta);
    }

    /** Set the cursor to a route point while retaining its fixed offset from the entity. */
    public void relocateCursor(Vec2f newCursorPosition) {
        translate(newCursorPosition.subtract(cursorPosition));
    }

    /** Records a cursor tile only when it changes, preserving the prior distinct tile. */
    public void observeCursorTile(TileCoord cursorTile) {
        if (lastObservedCursorTile == null) {
            lastObservedCursorTile = cursorTile;
        } else if (!lastObservedCursorTile.equals(cursorTile)) {
            previousDistinctCursorTile = lastObservedCursorTile;
            lastObservedCursorTile = cursorTile;
        }
    }

    public boolean visitStateMatches(TileCoord goalTile, int checkpointIndex) {
        return goalTile.equals(visitGoalTile) && checkpointIndex == visitGoalCheckpointIndex;
    }

    /** Resets center-visit history whenever the route target changes, even in the same tile. */
    public void resetVisitState(TileCoord goalTile, int checkpointIndex) {
        visitGoalTile = goalTile;
        visitGoalCheckpointIndex = checkpointIndex;
        lastObservedCursorTile = null;
        previousDistinctCursorTile = null;
        passedTileCenters.clear();
    }

    public void alertShown() {
        alertsShown++;
    }
}
