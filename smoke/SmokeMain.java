import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFormattedTextField;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Throwaway interactive smoke driver: launches the real workbench and operates
 * it through java.awt.Robot exactly like a user would, capturing a screenshot
 * after every step for visual inspection.
 */
public final class SmokeMain {
    private static Robot robot;
    private static SimulatorWorkbench window;
    private static Path shots;
    private static final List<String> failures = new ArrayList<>();

    private SmokeMain() {
    }

    public static void main(String[] args) {
        try {
            runSmoke();
        } catch (Throwable error) {
            System.out.println("SMOKE FAIL: unhandled " + error);
            error.printStackTrace();
            if (window != null) {
                try {
                    SwingUtilities.invokeAndWait(() -> window.dispose());
                } catch (Exception ignored) {
                }
            }
            System.exit(1);
        }
    }

    private static void runSmoke() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Smoke test requires a desktop session.");
            System.exit(2);
        }
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeAndWait(() -> {
            window = new SimulatorWorkbench();
            window.setVisible(true);
        });
        robot = new Robot();
        shots = Files.createDirectories(Path.of("smoke-shots"));
        settle(1500);

        shot("01-initial");

        // Paint three wall cells with the wall tool.
        click(requireToggleButton("墙"));
        SimulationCanvas canvas = require(SimulationCanvas.class);
        for (int x = 2; x <= 4; x++) {
            clickWorld(canvas, x + 0.5d, 6.5d);
            settle(250);
        }
        shot("02-walls");

        // Ctrl+Z removes the walls, Ctrl+Y brings them back.
        hotkey(KeyEvent.VK_Z, true);
        hotkey(KeyEvent.VK_Z, true);
        hotkey(KeyEvent.VK_Z, true);
        settle(400);
        shot("03-after-undo");
        hotkey(KeyEvent.VK_Y, true);
        hotkey(KeyEvent.VK_Y, true);
        hotkey(KeyEvent.VK_Y, true);
        settle(400);
        shot("04-after-redo");

        // Space starts and pauses playback.
        hotkey(KeyEvent.VK_SPACE, false);
        settle(1800);
        check(toggleButtonOrNull("Ⅱ") != null, "Space did not start playback (pause glyph missing)");
        shot("05-playing");
        hotkey(KeyEvent.VK_SPACE, false);
        settle(500);
        check(toggleButtonOrNull("▶") != null, "Space did not pause playback");
        shot("06-paused");

        JSlider slider = require(JSlider.class);
        int maxBefore = slider.getMaximum();
        check(maxBefore > 10, "Playback generated too few frames: " + maxBefore);
        System.out.println("slider max before injection: " + maxBefore);

        // Seek to frame 2 through the exact-time field.
        JTextField timeInput = requireTimeInput();
        click(timeInput);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_2);
        robot.keyRelease(KeyEvent.VK_2);
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        settle(1800);
        check(slider.getValue() == 2, "Seek to frame 2 did not land, slider at " + slider.getValue());
        shot("07-seeked-frame-2");

        // Inject a stun below the frontier, then step five frames with N.
        List<JButton> injectButtons = buttonsWithText("注入");
        check(injectButtons.size() == 2, "Expected two inject buttons, found " + injectButtons.size());
        if (injectButtons.size() >= 1) {
            JButton stunInject = injectButtons.get(0);
            stunInject.scrollRectToVisible(
                    new Rectangle(0, 0, stunInject.getWidth(), stunInject.getHeight()));
            settle(600);
            click(stunInject);
            settle(500);
            if (!labelContains("已安排")) {
                click(stunInject);
                settle(500);
            }
            check(labelContains("已安排"), "Stun injection was not recorded");
        }
        // Bare letters are dispatched at the Swing level: on this machine the
        // Chinese IME swallows Robot-synthesized letter keys before Java sees
        // them, which is OS behavior, not app behavior. The dispatched events
        // still traverse the real InputMap/ActionMap chain from the focus owner.
        for (int i = 0; i < 5; i++) {
            dispatchKey(KeyEvent.VK_N);
            settle(200);
        }
        settle(500);
        System.out.println("slider value after N steps: " + slider.getValue()
                + " max " + slider.getMaximum());
        check(slider.getValue() == 7 && slider.getMaximum() == 7,
                "Timeline was not truncated to frame 7 after the injection, value "
                        + slider.getValue() + " max " + slider.getMaximum());
        JButton stepButton = buttonWithTooltip("推进一帧（N）");
        check(stepButton != null, "Toolbar step button not found");
        if (stepButton != null) {
            click(stepButton);
            settle(400);
            check(slider.getValue() == 8, "Toolbar step did not advance after the injection");
        }
        shot("08-stunned-after-injection");
        int maxAfter = slider.getMaximum();
        System.out.println("slider max after injection:  " + maxAfter);

        if (failures.isEmpty()) {
            System.out.println("SMOKE PASS (" + (shots.toAbsolutePath()) + ")");
        } else {
            for (String failure : failures) {
                System.out.println("SMOKE FAIL: " + failure);
            }
        }
        SwingUtilities.invokeAndWait(() -> window.dispose());
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static void settle(int millis) {
        robot.waitForIdle();
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void shot(String name) {
        robot.waitForIdle();
        Point origin = window.getLocationOnScreen();
        BufferedImage image = robot.createScreenCapture(
                new Rectangle(origin.x, origin.y, window.getWidth(), window.getHeight()));
        try {
            Path file = shots.resolve(name + ".png");
            ImageIO.write(image, "png", file.toFile());
            System.out.println("captured " + name);
        } catch (IOException error) {
            failures.add("Screenshot failed: " + error.getMessage());
        }
    }

    private static void click(Component component) {
        Point point = component.getLocationOnScreen();
        robot.mouseMove(point.x + component.getWidth() / 2, point.y + component.getHeight() / 2);
        settle(150);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        settle(200);
    }

    private static void clickWorld(SimulationCanvas canvas, double worldX, double worldY) {
        Point relative = canvas.canvasPointForWorld(worldX, worldY);
        Point origin = canvas.getLocationOnScreen();
        robot.mouseMove(origin.x + relative.x, origin.y + relative.y);
        settle(150);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        settle(200);
    }

    private static void hotkey(int key, boolean control) {
        if (control) {
            robot.keyPress(KeyEvent.VK_CONTROL);
        }
        robot.keyPress(key);
        robot.keyRelease(key);
        if (control) {
            robot.keyRelease(KeyEvent.VK_CONTROL);
        }
        settle(120);
    }

    /** Sends a pressed/released pair straight to the focus owner on the EDT. */
    private static void dispatchKey(int vk) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                Component owner = java.awt.KeyboardFocusManager
                        .getCurrentKeyboardFocusManager().getFocusOwner();
                Component target = owner == null ? window : owner;
                long when = System.currentTimeMillis();
                target.dispatchEvent(new java.awt.event.KeyEvent(target,
                        java.awt.event.KeyEvent.KEY_PRESSED, when, 0, vk,
                        java.awt.event.KeyEvent.CHAR_UNDEFINED));
                target.dispatchEvent(new java.awt.event.KeyEvent(target,
                        java.awt.event.KeyEvent.KEY_RELEASED, when + 1, 0, vk,
                        java.awt.event.KeyEvent.CHAR_UNDEFINED));
            });
        } catch (Exception error) {
            failures.add("Key dispatch failed: " + error.getMessage());
        }
        settle(120);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures.add(message);
        }
    }

    private static <T extends Component> T require(Class<T> type) {
        T result = findIn(window, type);
        if (result == null) {
            failures.add("Component not found: " + type.getSimpleName());
        }
        return result;
    }

    private static JTextField requireTimeInput() {
        JTextField result = findTimeInput(window);
        if (result == null) {
            failures.add("Time input field not found");
        }
        return result;
    }

    private static JToggleButton requireToggleButton(String text) {
        JToggleButton result = toggleButtonOrNull(text);
        if (result == null) {
            failures.add("Toggle button not found: " + text);
        }
        return result;
    }

    private static JToggleButton toggleButtonOrNull(String textOrTooltip) {
        for (JToggleButton button : collect(window, JToggleButton.class, new ArrayList<>())) {
            if (textOrTooltip.equals(button.getText()) || textOrTooltip.equals(button.getToolTipText())) {
                return button;
            }
        }
        return null;
    }

    private static List<JButton> buttonsWithText(String text) {
        List<JButton> matches = new ArrayList<>();
        for (JButton button : collect(window, JButton.class, new ArrayList<>())) {
            if (text.equals(button.getText())) {
                matches.add(button);
            }
        }
        return matches;
    }

    private static JButton buttonWithTooltip(String tooltip) {
        for (JButton button : collect(window, JButton.class, new ArrayList<>())) {
            if (tooltip.equals(button.getToolTipText())) {
                return button;
            }
        }
        return null;
    }

    private static void dumpLabelsContaining(String text) {
        for (javax.swing.JLabel label : collect(window, javax.swing.JLabel.class, new ArrayList<>())) {
            if (label.getText() != null && label.getText().contains(text)) {
                System.out.println("status label: " + label.getText());
            }
        }
    }

    private static boolean labelContains(String text) {
        for (javax.swing.JLabel label : collect(window, javax.swing.JLabel.class, new ArrayList<>())) {
            if (label.getText() != null && label.getText().contains(text)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T findIn(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                return (T) child;
            }
            if (child instanceof Container container) {
                T nested = findIn(container, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static JTextField findTimeInput(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField field && !(child instanceof JFormattedTextField)) {
                return field;
            }
            if (child instanceof Container container) {
                JTextField nested = findTimeInput(container);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> List<T> collect(Container root, Class<T> type, List<T> into) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                into.add((T) child);
            }
            if (child instanceof Container container) {
                collect(container, type, into);
            }
        }
        return into;
    }
}
