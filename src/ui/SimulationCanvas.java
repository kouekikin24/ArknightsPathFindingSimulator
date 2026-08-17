import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** World-coordinate map renderer. Every drawing and hit test uses this camera. */
public final class SimulationCanvas extends JComponent {
    private static final int PADDING = 24;
    private static final double MIN_ZOOM = 0.1d;
    private static final double MAX_ZOOM_MULTIPLIER = 300d;
    private static final Color BACKGROUND = new Color(234, 240, 235);
    private static final Color GRID_LINE = new Color(191, 204, 196);
    private static final Color PATH = new Color(68, 135, 116);
    private static final Color TRAJECTORY = new Color(198, 57, 46);
    private static final double TRAJECTORY_HIT_RADIUS = 30d;
    private static final int HOVER_INVALIDATION_WIDTH = 520;
    private static final int HOVER_INVALIDATION_HEIGHT = 140;
    private static final Color ENTITY = new Color(222, 89, 64);
    private static final Color CURSOR = new Color(38, 50, 45);
    private static final Color SPAWN = new Color(43, 137, 91);
    private static final Color ENDPOINT = new Color(184, 61, 67);
    private static final Color CHECKPOINT = new Color(45, 122, 171);

    private final Consumer<UiCell> cellHandler;
    private UiSnapshot snapshot;
    private List<UiSnapshot> trajectoryStates = List.of();
    private boolean showPath;
    private boolean showTrajectory = true;
    private boolean browseMode;
    private UiCell lastDraggedCell;
    private Point mapPanStart;
    private Point mapPanViewStart;
    private Point hoverPosition;
    private UiSnapshot hoverSample;
    private double zoom = 1d;
    private int viewportWidth = 1;
    private int viewportHeight = 1;
    private Point requestedViewPosition;
    private Runnable zoomChangeListener = () -> { };

    public SimulationCanvas(Consumer<UiCell> cellHandler) {
        this.cellHandler = cellHandler;
        setOpaque(true);
        setBackground(BACKGROUND);
        setPreferredSize(new Dimension(760, 600));
        setMinimumSize(new Dimension(200, 160));
        MouseAdapter pointerHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (browseMode && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    beginMapPan(event);
                    updateHover(event);
                    return;
                }
                if (!javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                lastDraggedCell = null;
                sendCell(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (browseMode && mapPanStart != null) {
                    panMap(event);
                    updateHover(event);
                    return;
                }
                if (!javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                sendCell(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (mapPanStart != null) {
                    endMapPan();
                    updateHover(event);
                    return;
                }
                lastDraggedCell = null;
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                updateHover(event);
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                updateHover(event);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                clearHover();
            }

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent event) {
                event.consume();
                double rotation = event.getPreciseWheelRotation();
                if (rotation == 0d) {
                    return;
                }
                double factor = Math.pow(1.2d, -rotation);
                Point anchor = event.getPoint();
                double worldX = canvasToWorldX(anchor.x);
                double worldY = canvasToWorldY(anchor.y);
                Point viewPosition = viewportViewPosition();
                Point anchorInViewport = new Point(anchor.x - viewPosition.x, anchor.y - viewPosition.y);
                setZoomKeepingWorld(zoom * factor, worldX, worldY, anchorInViewport, viewPosition);
                javax.swing.SwingUtilities.invokeLater(SimulationCanvas.this::applyRequestedViewPosition);
            }
        };
        addMouseListener(pointerHandler);
        addMouseMotionListener(pointerHandler);
        addMouseWheelListener(pointerHandler);
    }

    public void setSnapshot(UiSnapshot value) {
        snapshot = value;
        refreshHoverSample();
        updatePreferredSize();
        repaint();
    }

    /**
     * Sets the trajectory samples. Callers must pass lists they never mutate
     * afterwards; the canvas keeps the reference so a playback frame does not
     * copy an ever-growing timeline.
     */
    public void setTrajectory(List<UiSnapshot> states) {
        trajectoryStates = states == null ? List.of() : states;
        refreshHoverSample();
        repaint();
    }

    public void setShowPath(boolean value) {
        showPath = value;
        repaint();
    }

    public void setShowTrajectory(boolean value) {
        showTrajectory = value;
        refreshHoverSample();
        repaint();
    }

    /** Enables pixel-for-pixel viewport panning with the primary mouse button. */
    public void setBrowseMode(boolean value) {
        browseMode = value;
        mapPanStart = null;
        mapPanViewStart = null;
        setCursor(Cursor.getPredefinedCursor(value ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** Called on the EDT after this canvas accepts a new camera zoom. */
    public void setZoomChangeListener(Runnable listener) {
        zoomChangeListener = listener == null ? () -> { } : listener;
    }

    public double zoom() {
        return zoom;
    }

    public double maximumZoom() {
        return fitZoom() * MAX_ZOOM_MULTIPLIER;
    }

    public double minimumZoom() {
        return MIN_ZOOM;
    }

    public double fitZoom() {
        if (snapshot == null) {
            return 1d;
        }
        double width = Math.max(1d, viewportWidth - PADDING * 2d);
        double height = Math.max(1d, viewportHeight - PADDING * 2d);
        return Math.max(MIN_ZOOM, Math.min(width / snapshot.width(), height / snapshot.height()) * 0.92d);
    }

    /** Zoom relative to the map-fit camera, where 100% means the entire map fits. */
    public double zoomPercent() {
        return zoom / fitZoom() * 100d;
    }

    public void setViewportSize(int width, int height) {
        double previousZoom = zoom;
        viewportWidth = Math.max(1, width);
        viewportHeight = Math.max(1, height);
        zoom = clampZoom(zoom);
        updatePreferredSize();
        repaint();
        notifyZoomChanged(previousZoom);
    }

    public void setZoom(double value) {
        double previousZoom = zoom;
        zoom = clampZoom(value);
        updatePreferredSize();
        repaint();
        notifyZoomChanged(previousZoom);
    }

    /** Zoom while preserving a world point under a viewport anchor. */
    public void setZoomKeepingWorld(double value, double worldX, double worldY,
                                    Point anchorInViewport, Point currentViewPosition) {
        double previousZoom = zoom;
        zoom = clampZoom(value);
        updatePreferredSize();
        int viewX = currentViewPosition == null ? 0 : currentViewPosition.x;
        int viewY = currentViewPosition == null ? 0 : currentViewPosition.y;
        int canvasX = worldToCanvasX(worldX);
        int canvasY = worldToCanvasY(worldY);
        requestedViewPosition = new Point(
                Math.max(0, canvasX - (anchorInViewport == null ? 0 : anchorInViewport.x)),
                Math.max(0, canvasY - (anchorInViewport == null ? 0 : anchorInViewport.y)));
        if (currentViewPosition != null && anchorInViewport == null) {
            requestedViewPosition = new Point(viewX, viewY);
        }
        repaint();
        notifyZoomChanged(previousZoom);
    }

    /**
     * Repositions the owning viewport after a zoom operation. This intentionally
     * lives beside the camera math so mouse-wheel callers cannot forget to apply
     * the coordinate-preserving scroll adjustment.
     */
    public void applyRequestedViewPosition() {
        Point position = consumeRequestedViewPosition();
        if (position == null) {
            return;
        }
        javax.swing.JViewport viewport = owningViewport();
        if (viewport == null) {
            requestedViewPosition = position;
            return;
        }
        Dimension extent = viewport.getExtentSize();
        Dimension preferred = getPreferredSize();
        int maxX = Math.max(0, preferred.width - extent.width);
        int maxY = Math.max(0, preferred.height - extent.height);
        viewport.setViewPosition(new Point(Math.min(position.x, maxX), Math.min(position.y, maxY)));
    }

    public Point consumeRequestedViewPosition() {
        Point result = requestedViewPosition;
        requestedViewPosition = null;
        return result;
    }

    public UiCell cellAt(int x, int y) {
        if (snapshot == null) {
            return null;
        }
        double worldX = canvasToWorldX(x);
        double worldY = canvasToWorldY(y);
        int cellX = (int) Math.floor(worldX);
        int cellY = (int) Math.floor(worldY);
        if (cellX < 0 || cellX >= snapshot.width() || cellY < 0 || cellY >= snapshot.height()) {
            return null;
        }
        return new UiCell(cellX, cellY);
    }

    public UiCell cellAtViewport(int viewportX, int viewportY, Point viewPosition) {
        Point position = viewPosition == null ? new Point() : viewPosition;
        return cellAt(viewportX + position.x, viewportY + position.y);
    }

    /** Pixel coordinate for a world point, exposed for deterministic headless checks. */
    public Point canvasPointForWorld(double worldX, double worldY) {
        return new Point(worldToCanvasX(worldX), worldToCanvasY(worldY));
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleSimulationCanvas();
        }
        return accessibleContext;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        UiSnapshot sample = nearestTrajectorySampleAt(event.getX(), event.getY());
        return sample == null ? null : formatHoverPosition(sample);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setColor(getBackground());
            canvas.fillRect(0, 0, getWidth(), getHeight());
            if (snapshot == null) {
                return;
            }
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            drawTerrain(canvas);
            if (showTrajectory) {
                drawTrajectory(canvas);
            }
            if (showPath) {
                drawPathSegments(canvas);
                drawNextNode(canvas);
            }
            drawMarkers(canvas);
            drawUnit(canvas);
            drawHoverSampleMarker(canvas);
            drawHoverPosition(canvas);
        } finally {
            canvas.dispose();
        }
    }

    private void drawTerrain(Graphics2D canvas) {
        for (int y = 0; y < snapshot.height(); y++) {
            for (int x = 0; x < snapshot.width(); x++) {
                int left = worldToCanvasX(x);
                int top = worldToCanvasY(y);
                int size = worldLengthToPixels(1d);
                UiTerrain terrain = snapshot.terrainAt(new UiCell(x, y));
                canvas.setColor(terrainColor(terrain));
                canvas.fillRect(left, top, size, size);
                drawTerrainDetail(canvas, terrain, left, top, size);
                canvas.setColor(GRID_LINE);
                canvas.drawRect(left, top, size, size);
            }
        }
    }

    private void drawTerrainDetail(Graphics2D canvas, UiTerrain terrain, int left, int top, int size) {
        if (size < 10) {
            return;
        }
        int inset = Math.max(3, size / 6);
        switch (terrain) {
            case OPEN -> { }
            case BOX -> {
                canvas.setColor(new Color(126, 61, 48));
                canvas.drawRect(left + inset, top + inset, size - inset * 2, size - inset * 2);
                canvas.drawLine(left + inset, top + inset, left + size - inset, top + size - inset);
                canvas.drawLine(left + size - inset, top + inset, left + inset, top + size - inset);
            }
            case PIT -> {
                canvas.setColor(new Color(128, 84, 22));
                canvas.fillOval(left + inset, top + inset, size - inset * 2, size - inset * 2);
                canvas.setColor(new Color(245, 210, 112));
                canvas.drawOval(left + inset + 2, top + inset + 2,
                        size - inset * 2 - 4, size - inset * 2 - 4);
            }
            case WALL -> {
                canvas.setColor(new Color(65, 77, 72));
                int stripe = Math.max(3, size / 5);
                for (int offset = stripe; offset < size; offset += stripe * 2) {
                    canvas.fillRect(left, top + offset, size, Math.max(1, stripe / 2));
                }
            }
        }
    }

    private void drawTrajectory(Graphics2D canvas) {
        if (trajectoryStates.size() < 2) {
            return;
        }
        canvas.setColor(TRAJECTORY);
        canvas.setStroke(new BasicStroke(trajectoryStrokeWidth(),
                BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        for (int index = 1; index < trajectoryStates.size(); index++) {
            UiSnapshot previous = trajectoryStates.get(index - 1);
            UiSnapshot current = trajectoryStates.get(index);
            if (current.trajectoryBreak()) {
                continue;
            }
            Path2D.Float segment = new Path2D.Float();
            segment.moveTo(worldToCanvasXFloat(previous.entityPosition().x()),
                    worldToCanvasYFloat(previous.entityPosition().y()));
            segment.lineTo(worldToCanvasXFloat(current.entityPosition().x()),
                    worldToCanvasYFloat(current.entityPosition().y()));
            canvas.draw(segment);
        }
    }

    private UiSnapshot nearestTrajectorySampleAt(int canvasX, int canvasY) {
        if (!showTrajectory || trajectoryStates.isEmpty()) {
            return null;
        }
        double bestDistanceSquared = TRAJECTORY_HIT_RADIUS * TRAJECTORY_HIT_RADIUS;
        UiSnapshot result = null;
        for (UiSnapshot sample : trajectoryStates) {
            double sampleX = worldToCanvasXFloat(sample.entityPosition().x());
            double sampleY = worldToCanvasYFloat(sample.entityPosition().y());
            double distanceSquared = distanceSquared(canvasX, canvasY, sampleX, sampleY);
            if (distanceSquared > bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            result = sample;
        }
        return result;
    }

    private void updateHover(MouseEvent event) {
        Rectangle dirty = hoverInvalidationBounds();
        hoverPosition = event.getPoint();
        refreshHoverSample();
        repaintHover(dirty);
    }

    private void refreshHoverSample() {
        hoverSample = hoverPosition == null ? null : nearestTrajectorySampleAt(hoverPosition.x, hoverPosition.y);
    }

    private void clearHover() {
        if (hoverPosition == null && hoverSample == null) {
            return;
        }
        Rectangle dirty = hoverInvalidationBounds();
        hoverPosition = null;
        hoverSample = null;
        repaintHover(dirty);
    }

    /** Invalidates only the label/marker neighbourhood while the viewport is panning. */
    private void repaintHover(Rectangle previousBounds) {
        Rectangle currentBounds = hoverInvalidationBounds();
        Rectangle dirty = previousBounds == null ? currentBounds : new Rectangle(previousBounds);
        if (dirty == null) {
            return;
        }
        if (currentBounds != null) {
            dirty.add(currentBounds);
        }
        repaint(dirty.x, dirty.y, dirty.width, dirty.height);
    }

    private Rectangle hoverInvalidationBounds() {
        if (hoverPosition == null || hoverSample == null) {
            return null;
        }
        return new Rectangle(hoverPosition.x - HOVER_INVALIDATION_WIDTH / 2,
                hoverPosition.y - HOVER_INVALIDATION_HEIGHT / 2,
                HOVER_INVALIDATION_WIDTH, HOVER_INVALIDATION_HEIGHT);
    }

    private void drawHoverPosition(Graphics2D canvas) {
        if (hoverPosition == null || hoverSample == null) {
            return;
        }
        String text = formatHoverPosition(hoverSample);
        canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        java.awt.FontMetrics metrics = canvas.getFontMetrics();
        int padding = 6;
        int width = metrics.stringWidth(text) + padding * 2;
        int height = metrics.getHeight() + padding * 2;
        int left = hoverPosition.x + 12;
        int top = hoverPosition.y + 12;
        if (left + width > getWidth() - 2) {
            left = hoverPosition.x - 12 - width;
        }
        if (top + height > getHeight() - 2) {
            top = hoverPosition.y - 12 - height;
        }
        left = Math.max(2, Math.min(left, Math.max(2, getWidth() - width - 2)));
        top = Math.max(2, Math.min(top, Math.max(2, getHeight() - height - 2)));
        canvas.setColor(new Color(29, 44, 38, 230));
        canvas.fillRoundRect(left, top, width, height, 4, 4);
        canvas.setColor(Color.WHITE);
        canvas.drawString(text, left + padding, top + padding + metrics.getAscent());
    }

    /** Marks only the hovered real frame sample without obscuring adjacent trajectory frames. */
    private void drawHoverSampleMarker(Graphics2D canvas) {
        if (hoverSample == null) {
            return;
        }
        int x = worldToCanvasX(hoverSample.entityPosition().x());
        int y = worldToCanvasY(hoverSample.entityPosition().y());
        canvas.setColor(TRAJECTORY);
        canvas.fillOval(x - 1, y - 1, 3, 3);
    }

    private static String formatHoverPosition(UiSnapshot sample) {
        return String.format(Locale.ROOT, "\u7b2c %d \u5e27\uff1a\u4f4d\u7f6e (%.4f, %.4f)", sample.frame(),
                sample.entityPosition().x(), sample.entityPosition().y());
    }

    private void beginMapPan(MouseEvent event) {
        javax.swing.JViewport viewport = owningViewport();
        if (viewport == null) {
            return;
        }
        mapPanStart = event.getPoint();
        mapPanViewStart = viewport.getViewPosition();
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
    }

    private void panMap(MouseEvent event) {
        javax.swing.JViewport viewport = owningViewport();
        if (viewport == null || mapPanViewStart == null) {
            return;
        }
        Dimension extent = viewport.getExtentSize();
        Dimension view = viewport.getViewSize();
        int maxX = Math.max(0, view.width - extent.width);
        int maxY = Math.max(0, view.height - extent.height);
        int deltaX = event.getX() - mapPanStart.x;
        int deltaY = event.getY() - mapPanStart.y;
        int nextX = Math.max(0, Math.min(maxX, mapPanViewStart.x - deltaX));
        int nextY = Math.max(0, Math.min(maxY, mapPanViewStart.y - deltaY));
        viewport.setViewPosition(new Point(nextX, nextY));
    }

    private void endMapPan() {
        mapPanStart = null;
        mapPanViewStart = null;
        setCursor(Cursor.getPredefinedCursor(browseMode ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private static double distanceSquared(double firstX, double firstY, double secondX, double secondY) {
        double deltaX = firstX - secondX;
        double deltaY = firstY - secondY;
        return deltaX * deltaX + deltaY * deltaY;
    }

    private float trajectoryStrokeWidth() {
        double width = 0.9d / Math.pow(Math.max(1d, zoom), 0.18d);
        return (float) Math.max(0.35d, Math.min(0.9d, width));
    }

    private void drawPathSegments(Graphics2D canvas) {
        Composite original = canvas.getComposite();
        canvas.setComposite(AlphaComposite.SrcOver.derive(0.48f));
        canvas.setColor(PATH);
        canvas.setStroke(new BasicStroke(Math.max(1.4f, (float) (zoom * 0.045d)),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (UiPathSegment segment : snapshot.pathSegments()) {
            int fromX = worldToCanvasX(segment.from().x() + 0.5d);
            int fromY = worldToCanvasY(segment.from().y() + 0.5d);
            int toX = worldToCanvasX(segment.to().x() + 0.5d);
            int toY = worldToCanvasY(segment.to().y() + 0.5d);
            canvas.drawLine(fromX, fromY, toX, toY);
            drawArrowHead(canvas, fromX, fromY, toX, toY, Math.max(5, worldLengthToPixels(0.14d)));
        }
        canvas.setComposite(original);
    }

    private void drawNextNode(Graphics2D canvas) {
        if (snapshot.nextNode() == null || zoom < 10d) {
            return;
        }
        UiCell nextNode = snapshot.nextNode();
        int left = worldToCanvasX(nextNode.x());
        int top = worldToCanvasY(nextNode.y());
        int size = worldLengthToPixels(1d);
        canvas.setColor(new Color(35, 93, 85));
        canvas.setStroke(new BasicStroke(Math.max(2f, (float) (zoom * 0.075d))));
        int inset = Math.max(4, size / 9);
        canvas.drawRoundRect(left + inset, top + inset, size - inset * 2, size - inset * 2,
                Math.max(4, inset), Math.max(4, inset));
    }

    private void drawMarkers(Graphics2D canvas) {
        // Spawn and checkpoint markers are editing aids.  Once simulation has
        // advanced, leave the actual entity trajectory unobscured.
        if (snapshot.frame() == 0) {
            drawMarker(canvas, snapshot.spawn(), SPAWN, "S", false);
            for (int index = 0; index < snapshot.checkpoints().size(); index++) {
                drawMarker(canvas, snapshot.checkpoints().get(index), CHECKPOINT,
                        Integer.toString(index + 1), true);
            }
        }
        drawMarker(canvas, snapshot.endpoint(), ENDPOINT, "E", false);
        if (snapshot.target() != null) {
            int x = worldToCanvasX(snapshot.target().x());
            int y = worldToCanvasY(snapshot.target().y());
            int radius = Math.max(6, worldLengthToPixels(0.14d));
            canvas.setColor(new Color(32, 104, 95));
            canvas.setStroke(new BasicStroke(Math.max(1.5f, (float) (zoom * 0.045d))));
            canvas.drawOval(x - radius, y - radius, radius * 2, radius * 2);
        }
    }

    private void drawMarker(Graphics2D canvas, UiPoint point, Color color, String text, boolean diamond) {
        int x = worldToCanvasX(point.x());
        int y = worldToCanvasY(point.y());
        int radius = Math.max(1, Math.min(Math.max(6, worldLengthToPixels(0.2d)),
                Math.max(1, worldLengthToPixels(0.5d) - 1)));
        canvas.setColor(color);
        if (!diamond) {
            canvas.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        } else {
            Path2D.Float shape = new Path2D.Float();
            shape.moveTo(x, y - radius);
            shape.lineTo(x + radius, y);
            shape.lineTo(x, y + radius);
            shape.lineTo(x - radius, y);
            shape.closePath();
            canvas.fill(shape);
        }
        if (radius >= 5) {
            canvas.setColor(Color.WHITE);
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, radius)));
            drawCenteredText(canvas, text, x, y);
        }
    }

    private void drawUnit(Graphics2D canvas) {
        UiPoint entityPosition = snapshot.entityPosition();
        int x = worldToCanvasX(entityPosition.x());
        int y = worldToCanvasY(entityPosition.y());
        drawVector(canvas, entityPosition, snapshot.inertiaVelocity(), new Color(196, 69, 51), 2.2f);
        drawVector(canvas, entityPosition, snapshot.givenDirection(), new Color(28, 118, 108), 0.72f);
        int radius = Math.max(8, worldLengthToPixels(0.2d));
        canvas.setColor(ENTITY);
        canvas.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        canvas.setColor(Color.WHITE);
        canvas.setStroke(new BasicStroke(Math.max(1.5f, (float) (zoom * 0.045d))));
        canvas.drawOval(x - radius, y - radius, radius * 2, radius * 2);

        UiPoint cursorPosition = snapshot.cursorPosition();
        int cursorX = worldToCanvasX(cursorPosition.x());
        int cursorY = worldToCanvasY(cursorPosition.y());
        int cross = Math.max(5, worldLengthToPixels(0.125d));
        canvas.setColor(CURSOR);
        canvas.setStroke(new BasicStroke(Math.max(1.4f, (float) (zoom * 0.04d))));
        canvas.drawLine(cursorX - cross, cursorY, cursorX + cross, cursorY);
        canvas.drawLine(cursorX, cursorY - cross, cursorX, cursorY + cross);
    }

    private void drawVector(Graphics2D canvas, UiPoint origin, UiPoint vector, Color color, float scale) {
        if (vector == null || (vector.x() == 0f && vector.y() == 0f)) {
            return;
        }
        int fromX = worldToCanvasX(origin.x());
        int fromY = worldToCanvasY(origin.y());
        int toX = Math.round(fromX + vector.x() * (float) zoom * scale);
        int toY = Math.round(fromY + vector.y() * (float) zoom * scale);
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(Math.max(1.5f, (float) (zoom * 0.045d)),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        canvas.drawLine(fromX, fromY, toX, toY);
        drawArrowHead(canvas, fromX, fromY, toX, toY, Math.max(5, worldLengthToPixels(0.125d)));
    }

    private static void drawArrowHead(Graphics2D canvas, int fromX, int fromY, int toX, int toY, int size) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double length = Math.hypot(dx, dy);
        if (length < 1d) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;
        double px = -uy;
        double py = ux;
        Path2D.Float head = new Path2D.Float();
        head.moveTo(toX, toY);
        head.lineTo((float) (toX - ux * size + px * size * 0.55d),
                (float) (toY - uy * size + py * size * 0.55d));
        head.lineTo((float) (toX - ux * size - px * size * 0.55d),
                (float) (toY - uy * size - py * size * 0.55d));
        head.closePath();
        canvas.fill(head);
    }

    private void sendCell(MouseEvent event) {
        UiCell cell = cellAt(event.getX(), event.getY());
        if (cell == null || cell.equals(lastDraggedCell)) {
            return;
        }
        lastDraggedCell = cell;
        cellHandler.accept(cell);
    }

    private int worldToCanvasX(double worldX) {
        return Math.round((float) (PADDING + worldX * zoom));
    }

    private int worldToCanvasY(double worldY) {
        return Math.round((float) (PADDING + worldY * zoom));
    }

    private float worldToCanvasXFloat(float worldX) {
        return (float) (PADDING + worldX * zoom);
    }

    private float worldToCanvasYFloat(float worldY) {
        return (float) (PADDING + worldY * zoom);
    }

    private double canvasToWorldX(int canvasX) {
        return worldXAtCanvas(canvasX);
    }

    private double canvasToWorldY(int canvasY) {
        return worldYAtCanvas(canvasY);
    }

    double worldXAtCanvas(double canvasX) {
        return (canvasX - PADDING) / zoom;
    }

    double worldYAtCanvas(double canvasY) {
        return (canvasY - PADDING) / zoom;
    }

    private int worldLengthToPixels(double worldLength) {
        return Math.max(1, (int) Math.round(worldLength * zoom));
    }

    private double clampZoom(double value) {
        if (!Double.isFinite(value)) {
            return 1d;
        }
        return Math.max(MIN_ZOOM, Math.min(maximumZoom(), value));
    }

    private void updatePreferredSize() {
        if (snapshot == null) {
            return;
        }
        int width = PADDING * 2 + worldLengthToPixels(snapshot.width());
        int height = PADDING * 2 + worldLengthToPixels(snapshot.height());
        Dimension next = new Dimension(Math.max(1, width), Math.max(1, height));
        if (!next.equals(getPreferredSize())) {
            setPreferredSize(next);
            revalidate();
        }
    }

    private Point viewportViewPosition() {
        javax.swing.JViewport viewport = owningViewport();
        return viewport == null ? new Point() : viewport.getViewPosition();
    }

    private javax.swing.JViewport owningViewport() {
        return (javax.swing.JViewport) javax.swing.SwingUtilities
                .getAncestorOfClass(javax.swing.JViewport.class, this);
    }

    private void notifyZoomChanged(double previousZoom) {
        if (Double.compare(previousZoom, zoom) != 0) {
            zoomChangeListener.run();
        }
    }

    private static void drawCenteredText(Graphics2D canvas, String text, int x, int y) {
        int width = canvas.getFontMetrics().stringWidth(text);
        int baseline = y + (canvas.getFontMetrics().getAscent() - canvas.getFontMetrics().getDescent()) / 2;
        canvas.drawString(text, x - width / 2, baseline);
    }

    static Color terrainColor(UiTerrain terrain) {
        return switch (terrain) {
            case OPEN -> new Color(249, 252, 249);
            case BOX -> new Color(194, 107, 84);
            case PIT -> new Color(223, 177, 62);
            case WALL -> new Color(81, 97, 91);
        };
    }

    protected class AccessibleSimulationCanvas extends AccessibleJComponent {
        @Override
        public String getAccessibleName() {
            return "寻路地图";
        }

        @Override
        public String getAccessibleDescription() {
            return "用于编辑地形和路线点的模拟地图";
        }
    }
}
