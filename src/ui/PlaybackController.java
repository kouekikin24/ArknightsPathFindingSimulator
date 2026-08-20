import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import java.util.function.Consumer;

/** Drives the playback timer and the play button; the session supplies frames. */
final class PlaybackController {
    private final SimulationSession session;
    private final JToggleButton playButton;
    private final JComboBox<String> playbackRate;
    private final JLabel operationStatus;
    private final Consumer<UiFrame> refresh;
    private final Timer timer;

    PlaybackController(SimulationSession session, JToggleButton playButton,
                       JComboBox<String> playbackRate, JLabel operationStatus,
                       Consumer<UiFrame> refresh) {
        this.session = session;
        this.playButton = playButton;
        this.playbackRate = playbackRate;
        this.operationStatus = operationStatus;
        this.refresh = refresh;
        this.timer = new Timer(33, event -> advance());
        timer.setCoalesce(true);
    }

    /** Follows the play button: starts the timer, or stops on deselect/terminal. */
    void toggle() {
        if (!playButton.isSelected()) {
            stop();
            return;
        }
        if (!session.canTick()) {
            operationStatus.setText("模拟已到达终态");
            stop();
            return;
        }
        playButton.setText("Ⅱ");
        playButton.setToolTipText("暂停（空格）");
        timer.start();
    }

    void stop() {
        timer.stop();
        playButton.setSelected(false);
        playButton.setText("▶");
        playButton.setToolTipText("运行（空格）");
    }

    private void advance() {
        UiFrame current = null;
        try {
            for (int index = 0; index < framesPerTimerTick(); index++) {
                current = session.tickFrame();
                if (session.isTerminal()) {
                    break;
                }
            }
        } catch (SimulationSession.TerminalStateException terminal) {
            stop();
        }
        refresh.accept(current == null ? session.snapshotFrame() : current);
        if (current != null && session.isTerminal()) {
            stop();
        }
    }

    private int framesPerTimerTick() {
        return switch (playbackRate.getSelectedIndex()) {
            case 1 -> 3;
            case 2 -> 10;
            default -> 1;
        };
    }
}
