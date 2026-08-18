import java.util.ArrayList;
import java.util.List;

/** A stage-wide immutable view of one frame: all units plus the shared playback time. */
public record UiFrame(int frame, float playTime, List<UiSnapshot> units) {

    public UiFrame {
        units = List.copyOf(units);
    }

    public UiSnapshot primary() {
        if (units.isEmpty()) {
            throw new IllegalStateException("Frame has no units");
        }
        return units.get(0);
    }
}
