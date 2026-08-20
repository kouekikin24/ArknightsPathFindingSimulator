import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.util.Locale;

/**
 * Owns the map viewport: initial fit, zoom-on-resize around the preserved
 * world center, and the zoom readout. Pure view logic; the session is never
 * touched here.
 */
final class ViewportCamera {
    private final JScrollPane mapScrollPane;
    private final SimulationCanvas canvas;
    private final JLabel zoomValue;
    private boolean initialFitPending = true;
    private int lastViewportWidth;
    private int lastViewportHeight;

    ViewportCamera(JScrollPane mapScrollPane, SimulationCanvas canvas, JLabel zoomValue) {
        this.mapScrollPane = mapScrollPane;
        this.canvas = canvas;
        this.zoomValue = zoomValue;
    }

    /** Reacts to viewport resizes: fits once, then keeps the world center stable. */
    void update() {
        int width = mapScrollPane.getViewport().getWidth();
        int height = mapScrollPane.getViewport().getHeight();
        if (width <= 1 || height <= 1) {
            return;
        }
        if (initialFitPending) {
            initialFitPending = false;
            lastViewportWidth = width;
            lastViewportHeight = height;
            canvas.setViewportSize(width, height);
            canvas.setZoom(canvas.fitZoom());
            mapScrollPane.getViewport().setViewPosition(new Point(0, 0));
            refreshZoomLabel();
            return;
        }
        if (width == lastViewportWidth && height == lastViewportHeight) {
            return;
        }
        Point oldView = mapScrollPane.getViewport().getViewPosition();
        int priorWidth = lastViewportWidth;
        int priorHeight = lastViewportHeight;
        if (priorWidth <= 1 || priorHeight <= 1) {
            priorWidth = width;
            priorHeight = height;
        }
        double oldCenterWorldX = canvas.worldXAtCanvas(oldView.x + priorWidth / 2d);
        double oldCenterWorldY = canvas.worldYAtCanvas(oldView.y + priorHeight / 2d);
        lastViewportWidth = width;
        lastViewportHeight = height;
        canvas.setViewportSize(width, height);
        canvas.setZoomKeepingWorld(canvas.zoom(), oldCenterWorldX, oldCenterWorldY,
                new Point(width / 2, height / 2), oldView);
        canvas.applyRequestedViewPosition();
        refreshZoomLabel();
    }

    /** The next update() refits the whole map, e.g. after the map size changed. */
    void requestFit() {
        initialFitPending = true;
        lastViewportWidth = -1;
        lastViewportHeight = -1;
        SwingUtilities.invokeLater(this::update);
    }

    void refreshZoomLabel() {
        zoomValue.setText(String.format(Locale.ROOT, "%.0f%%", canvas.zoomPercent()));
    }
}
