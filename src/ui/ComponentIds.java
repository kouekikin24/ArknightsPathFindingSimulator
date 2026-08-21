import javax.swing.JComponent;
import java.awt.Component;

/**
 * Stable IDs for every interactive control, in the spirit of the element
 * names that mature Swing test frameworks (AssertJ-Swing, Jemmy) require via
 * {@code setName}. The ID plus a Chinese label are stored on the component
 * itself, so the inspector overlay and docs/component-map.md can never drift
 * apart. IDs use a one-letter area prefix: M map, D dimensions, U units,
 * R movement, T tools, S spawn, E endpoint, C checkpoints, B combat,
 * P playback, V view, F files.
 */
final class ComponentIds {
    private static final Object LABEL_KEY = new Object();

    private ComponentIds() {
    }

    /** Tags a component at its creation site and returns it for fluent field init. */
    static <T extends JComponent> T tag(T component, String id, String label) {
        component.setName(id);
        component.putClientProperty(LABEL_KEY, label);
        return component;
    }

    static boolean isTagged(Component component) {
        return component instanceof JComponent jc && jc.getClientProperty(LABEL_KEY) != null;
    }

    /**
     * Nearest tagged component at or above the given one, so a spinner's inner
     * text field or a combo box's editor resolves to the tagged control itself.
     */
    static JComponent ownerOf(Component component) {
        Component current = component;
        while (current != null) {
            if (isTagged(current)) {
                return (JComponent) current;
            }
            current = current.getParent();
        }
        return null;
    }

    static String labelOf(JComponent component) {
        Object label = component.getClientProperty(LABEL_KEY);
        return label instanceof String text ? text : null;
    }

    /** The overlay caption, e.g. "C2 · 检查点坐标 Y". */
    static String describe(JComponent component) {
        return component.getName() + " · " + labelOf(component);
    }
}
