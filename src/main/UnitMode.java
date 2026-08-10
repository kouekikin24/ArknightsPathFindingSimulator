/** Only MOVE executes autonomous motion; route checks still occur in every mode. */
public enum UnitMode {
    MOVE,
    BLOCKED,
    STUNNED,
    DISPLACED,
    VANISHED,
    COMPLETED
}
