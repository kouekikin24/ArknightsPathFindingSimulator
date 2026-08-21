import java.util.Locale;

/** Pure display formatting shared by the workbench and the verify suite. */
final class UiFormat {
    private UiFormat() {
    }

    /** Row label: the active checkpoint carries a marker instead of stealing the selection. */
    static String checkpointRow(int index, UiCheckpoint checkpoint, boolean active) {
        return String.format(Locale.ROOT, "%s%02d  %s %s",
                active ? "▶ " : "　 ", index + 1, checkpoint.type().label(),
                checkpointDetail(checkpoint)).strip();
    }

    private static String checkpointDetail(UiCheckpoint checkpoint) {
        if (checkpoint.point() != null) {
            // Points are exact world coordinates; show up to 4 decimals, trimmed.
            return "(" + coordinate(checkpoint.point().x()) + ", "
                    + coordinate(checkpoint.point().y()) + ")";
        }
        if (checkpoint.type().usesSeconds()) {
            return String.format(Locale.ROOT, "%.1f s", checkpoint.value());
        }
        if (checkpoint.type().usesArea()) {
            return checkpoint.area() + " 区";
        }
        return "";
    }

    /** Up to 4 decimals with trailing zeros stripped: 5.5 stays "5.5", 6.0 becomes "6". */
    private static String coordinate(float value) {
        String text = String.format(Locale.ROOT, "%.4f", (double) value);
        int dot = text.indexOf('.');
        if (dot < 0) {
            return text;
        }
        int end = text.length();
        while (end > dot + 1 && text.charAt(end - 1) == '0') {
            end--;
        }
        return end == dot + 1 ? text.substring(0, dot) : text.substring(0, end);
    }

    static String checkpointLabel(UiSnapshot snapshot) {
        if (snapshot.completed()) {
            return "已完成";
        }
        int count = snapshot.checkpoints().size();
        return snapshot.activeCheckpoint() < count ? (snapshot.activeCheckpoint() + 1) + " / " + count : "终点";
    }

    static String statusLabel(UiSnapshot snapshot) {
        if (!snapshot.transition().isBlank()) {
            return snapshot.transition();
        }
        if (snapshot.completed()) {
            return "已到达终点";
        }
        // Stable while running: the per-frame avoidance refresh is shown in the
        // sidebar "避障" row, not here, so the footer does not flicker.
        return "就绪";
    }

    static String point(UiPoint point) {
        return point == null ? "-" : String.format(Locale.ROOT, "%.4f, %.4f", point.x(), point.y());
    }

    static String cell(UiCell cell) {
        return cell == null ? "-" : cell.x() + ", " + cell.y();
    }
}
