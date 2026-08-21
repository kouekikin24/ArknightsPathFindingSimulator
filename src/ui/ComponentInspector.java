import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;

/**
 * Component inspector for bug reports: hold Ctrl and hover any control to see
 * its stable ID and Chinese name (the IDs listed in docs/component-map.md).
 * The overlay paints on the frame's glass pane but reports contains() false,
 * so it never intercepts a single event.
 */
final class ComponentInspector {
    private static final Color ACCENT = new Color(47, 129, 247);
    private static final Color ACCENT_FILL = new Color(47, 129, 247, 36);
    private static final Color LABEL_BACKGROUND = new Color(24, 28, 26, 232);

    private final JFrame frame;
    private final JComponent glass;
    private boolean armed;
    private JComponent target;

    static void install(JFrame frame) {
        new ComponentInspector(frame);
    }

    private ComponentInspector(JFrame frame) {
        this.frame = frame;
        this.glass = new GlassPane();
        frame.setGlassPane(glass);
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event instanceof KeyEvent key) {
                onKey(key);
            } else if (armed) {
                updateTargetUnderMouse();
            }
        }, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    private void onKey(KeyEvent key) {
        if (key.getKeyCode() != KeyEvent.VK_CONTROL) {
            return;
        }
        if (key.getID() == KeyEvent.KEY_PRESSED && !armed) {
            armed = true;
            glass.setVisible(true);
            updateTargetUnderMouse();
        } else if (key.getID() == KeyEvent.KEY_RELEASED && armed) {
            armed = false;
            target = null;
            glass.setVisible(false);
        }
    }

    private void updateTargetUnderMouse() {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        JComponent owner = null;
        if (pointer != null) {
            Point point = pointer.getLocation();
            SwingUtilities.convertPointFromScreen(point, frame.getContentPane());
            Component deepest = SwingUtilities.getDeepestComponentAt(
                    frame.getContentPane(), point.x, point.y);
            owner = ComponentIds.ownerOf(deepest);
        }
        if (owner != target) {
            target = owner;
            glass.repaint();
        }
    }

    private final class GlassPane extends JComponent {
        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!armed || target == null || !target.isShowing() || target.getParent() == null) {
                return;
            }
            Rectangle bounds = SwingUtilities.convertRectangle(
                    target.getParent(), target.getBounds(), this);
            bounds = bounds.intersection(new Rectangle(0, 0, getWidth(), getHeight()));
            if (bounds.isEmpty()) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(ACCENT_FILL);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                g.setColor(ACCENT);
                g.setStroke(new BasicStroke(2f));
                g.drawRect(bounds.x + 1, bounds.y + 1, bounds.width - 3, bounds.height - 3);

                String text = ComponentIds.describe(target);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
                FontMetrics metrics = g.getFontMetrics();
                int labelWidth = metrics.stringWidth(text) + 14;
                int labelHeight = metrics.getHeight() + 6;
                int labelX = Math.max(4, Math.min(bounds.x, getWidth() - labelWidth - 4));
                int labelY = bounds.y + bounds.height + 4;
                if (labelY + labelHeight > getHeight() - 4) {
                    labelY = bounds.y - labelHeight - 4;
                }
                if (labelY < 4) {
                    labelY = 4;
                }
                g.setColor(LABEL_BACKGROUND);
                g.fillRoundRect(labelX, labelY, labelWidth, labelHeight, 8, 8);
                g.setColor(Color.WHITE);
                g.drawString(text, labelX + 7, labelY + metrics.getAscent() + 3);
            } finally {
                g.dispose();
            }
        }
    }
}
