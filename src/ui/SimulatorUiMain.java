import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/** Separate desktop entry point. The core simulator remains usable without this class. */
public final class SimulatorUiMain {
    private static final Color STALE_PIXEL_COLOR = new Color(211, 42, 174);

    private SimulatorUiMain() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && "--verify".equals(args[0])) {
            verifySession();
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("The desktop UI requires a graphical desktop session.");
            return;
        }
        configureLookAndFeel();
        SwingUtilities.invokeLater(() -> new SimulatorWorkbench().setVisible(true));
    }

    private static void verifySession() {
        SimulationSession session = new SimulationSession();
        UiSnapshot before = session.snapshot();
        UiSnapshot after = session.tick();
        if (before.width() < 2 || before.height() < 2
                || after.frame() != before.frame() + 1
                || !after.avoidanceRecomputed()) {
            throw new IllegalStateException("UI session did not advance as expected");
        }
        UiSnapshot reset = session.resetSimulation();
        UiSnapshot afterReset = session.tick();
        if (reset.frame() != 0 || afterReset.frame() != 1 || !afterReset.avoidanceRecomputed()) {
            throw new IllegalStateException("UI session did not reset its playback frame as expected");
        }
        verifyCanvas(after);
        verifyExactSeek();
        verifyTimeParsing();
        verifyPortalTrajectoryBreak();
        System.out.println("UI session verification passed.");
    }

    private static void verifyExactSeek() {
        SimulationSession seekSession = new SimulationSession();
        UiSnapshot expected = null;
        for (int frame = 0; frame <= 18; frame++) {
            expected = frame == 0 ? seekSession.snapshot() : seekSession.tick();
        }
        SimulationSession replay = new SimulationSession();
        UiSnapshot actual = replay.seekFrame(18);
        assertFloatBits(expected.entityPosition(), actual.entityPosition(), "entityPosition");
        assertFloatBits(expected.cursorPosition(), actual.cursorPosition(), "cursorPosition");
        assertFloatBits(expected.inertiaVelocity(), actual.inertiaVelocity(), "inertiaVelocity");
        if (actual.frame() != 18 || !SimulationSession.formatFrameTime(18).equals("18 / 30 s")) {
            throw new IllegalStateException("Exact frame seek/time display failed");
        }
        List<UiSnapshot> states = replay.generatedStates();
        if (states.size() != 19 || states.get(0).frame() != 0 || states.get(18).frame() != 18) {
            throw new IllegalStateException("Timeline does not contain S[0]..S[n]");
        }
    }

    private static void verifyTimeParsing() {
        if (SimulationSession.parseTimeToFrame("16") != 16
                || SimulationSession.parseTimeToFrame("16/30") != 16
                || SimulationSession.parseTimeToFrame("0.5") != 15
                || SimulationSession.parseTimeToFrame("1.2") != 36) {
            throw new IllegalStateException("Exact time parsing returned an incorrect frame");
        }
        try {
            SimulationSession.parseTimeToFrame("0.01");
            throw new IllegalStateException("Non-integral frame time was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("adjacent legal frames")) {
                throw new IllegalStateException("Non-integral frame error omitted adjacent frames");
            }
        }
    }

    private static void assertFloatBits(UiPoint expected, UiPoint actual, String label) {
        if (expected == null || actual == null
                || Float.floatToIntBits(expected.x()) != Float.floatToIntBits(actual.x())
                || Float.floatToIntBits(expected.y()) != Float.floatToIntBits(actual.y())) {
            throw new IllegalStateException("Seek mismatch in " + label);
        }
    }

    private static void verifyCanvas(UiSnapshot snapshot) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                SimulationCanvas canvas = new SimulationCanvas(cell -> {
                });
                canvas.setSnapshot(snapshot);
                canvas.setViewportSize(800, 560);
                if (canvas.fitZoom() <= 0d) {
                    throw new IllegalStateException("Fit zoom was not available for an 800x560 viewport");
                }
                canvas.setZoom(canvas.fitZoom());
                assertFitsViewport(canvas, 800, 560);
                canvas.setZoom(canvas.maximumZoom());
                if (canvas.zoom() < Math.max(800, 560)) {
                    throw new IllegalStateException("Maximum zoom does not cover viewport long edge");
                }
                java.awt.Point normalPoint = canvas.canvasPointForWorld(3.2, 2.2);
                UiCell normalHit = canvas.cellAt(normalPoint.x, normalPoint.y);
                canvas.setZoom(20);
                java.awt.Point zoomPoint = canvas.canvasPointForWorld(3.2, 2.2);
                UiCell zoomHit = canvas.cellAt(zoomPoint.x, zoomPoint.y);
                if (normalHit == null || !normalHit.equals(zoomHit)) {
                    throw new IllegalStateException("Camera inverse transform changed cell hit");
                }
                verifyMouseWheelZoom(canvas);
                verifyZoomAnchor(canvas);
                verifyTrajectoryTooltip(canvas);
                verifyCanvasPaint(canvas, 800, 560);
                canvas.setViewportSize(440, 320);
                canvas.setZoom(canvas.fitZoom());
                assertFitsViewport(canvas, 440, 320);
                verifyCanvasPaint(canvas, 440, 320);
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while verifying the UI canvas", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("UI canvas verification failed", exception.getCause());
        }
    }

    private static void verifyMouseWheelZoom(SimulationCanvas canvas) {
        canvas.setZoom(20d);
        double before = canvas.zoom();
        MouseWheelEvent event = new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
                0L, 0, 80, 60, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1);
        for (java.awt.event.MouseWheelListener listener : canvas.getMouseWheelListeners()) {
            listener.mouseWheelMoved(event);
        }
        if (canvas.zoom() <= before) {
            throw new IllegalStateException("Plain mouse wheel did not zoom in");
        }
    }

    private static void verifyZoomAnchor(SimulationCanvas canvas) {
        canvas.setZoom(20d);
        Point view = new Point(100, 80);
        Point anchor = new Point(180, 120);
        double worldX = canvas.worldXAtCanvas(view.x + anchor.x);
        double worldY = canvas.worldYAtCanvas(view.y + anchor.y);
        canvas.setZoomKeepingWorld(40d, worldX, worldY, anchor, view);
        Point requested = canvas.consumeRequestedViewPosition();
        if (requested == null) {
            throw new IllegalStateException("Mouse zoom did not request an anchored viewport position");
        }
        double restoredX = canvas.worldXAtCanvas(requested.x + anchor.x);
        double restoredY = canvas.worldYAtCanvas(requested.y + anchor.y);
        if (Math.abs(restoredX - worldX) > 0.03d || Math.abs(restoredY - worldY) > 0.03d) {
            throw new IllegalStateException("Mouse zoom changed the world coordinate under the anchor");
        }
    }

    private static void verifyTrajectoryTooltip(SimulationCanvas canvas) {
        UiSnapshot first = tooltipSnapshot(3, new UiPoint(2f, 2f), false);
        UiSnapshot second = tooltipSnapshot(4, new UiPoint(4f, 2f), false);
        canvas.setTrajectory(List.of(first, second));
        canvas.setShowTrajectory(true);
        canvas.setZoom(40d);
        Point midpoint = canvas.canvasPointForWorld(3d, 2f);
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0,
                midpoint.x, midpoint.y, 0, false);
        String tooltip = canvas.getToolTipText(event);
        if (tooltip == null || !tooltip.contains("实际轨迹") || !tooltip.contains("帧 3 -> 4")
                || !tooltip.contains("位置")) {
            throw new IllegalStateException("Trajectory hover did not show a real sampled position");
        }
        canvas.setTrajectory(List.of());
    }

    private static void verifyPortalTrajectoryBreak() {
        GridMap map = new GridMap(8, 3);
        Route route = new Route(new Vec2f(0.5f, 1.5f), new Vec2f(7.5f, 1.5f),
                List.of(Checkpoint.disappear(), Checkpoint.appearAt(new Vec2f(6.5f, 1.5f))),
                MovementMode.GROUND, true, true, false);
        PathfindingSimulator simulator = new PathfindingSimulator(map, route, UnitConfig.normalGround(1f));
        FrameTrace trace = simulator.tick(0L);
        if (!trace.transition().contains("DISAPPEAR") || !trace.transition().contains("APPEAR_AT_POS")
                || Math.abs(trace.entityAfter().x() - trace.entityBefore().x()) <= 1f) {
            throw new IllegalStateException("Portal setup did not create a real discontinuous frame trace");
        }
        UiSnapshot previous = portalSnapshot(0, new UiPoint(trace.entityBefore().x(), trace.entityBefore().y()), false);
        UiSnapshot current = portalSnapshot(1, new UiPoint(trace.entityAfter().x(), trace.entityAfter().y()), true);
        if (!current.trajectoryBreak()) {
            throw new IllegalStateException("Portal frame did not mark the actual trajectory as broken");
        }
        SimulationCanvas canvas = new SimulationCanvas(cell -> { });
        canvas.setSnapshot(current);
        canvas.setTrajectory(List.of(previous, current));
        canvas.setViewportSize(800, 560);
        canvas.setZoom(60d);
        canvas.setSize(canvas.getPreferredSize());
        BufferedImage image = new BufferedImage(canvas.getPreferredSize().width,
                canvas.getPreferredSize().height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            canvas.paint(graphics);
        } finally {
            graphics.dispose();
        }
        Point midpoint = canvas.canvasPointForWorld(3.5d, 1.5d);
        if (isTrajectoryRed(image, midpoint.x, midpoint.y)) {
            throw new IllegalStateException("Portal trajectory drew a false cross-map segment");
        }
    }

    private static UiSnapshot portalSnapshot(int frame, UiPoint entityPosition, boolean trajectoryBreak) {
        return new UiSnapshot(8, 3, java.util.Collections.nCopies(24, UiTerrain.OPEN),
                new UiPoint(0.5f, 1.5f), new UiPoint(7.5f, 1.5f), List.of(),
                UiMovementMode.GROUND, 1f, true, frame, "移动", 0, false,
                entityPosition, entityPosition, UiPoint.ZERO, UiPoint.ZERO, UiPoint.ZERO,
                new UiCell(0, 1), null, null, false, "APPEAR_AT_POS", 0f, List.of(), trajectoryBreak);
    }

    private static UiSnapshot tooltipSnapshot(int frame, UiPoint entityPosition, boolean trajectoryBreak) {
        return new UiSnapshot(8, 3, java.util.Collections.nCopies(24, UiTerrain.OPEN),
                new UiPoint(0.5f, 1.5f), new UiPoint(7.5f, 1.5f), List.of(),
                UiMovementMode.GROUND, 1f, true, frame, "移动", 0, false,
                entityPosition, entityPosition, UiPoint.ZERO, UiPoint.ZERO, UiPoint.ZERO,
                new UiCell(0, 1), null, null, false, "", 0f, List.of(), trajectoryBreak);
    }

    private static boolean isTrajectoryRed(BufferedImage image, int x, int y) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return false;
        }
        Color pixel = new Color(image.getRGB(x, y), true);
        return pixel.getRed() > 150 && pixel.getGreen() < 110 && pixel.getBlue() < 100;
    }

    private static void assertFitsViewport(SimulationCanvas canvas, int width, int height) {
        java.awt.Dimension size = canvas.getPreferredSize();
        if (size.width > width || size.height > height) {
            throw new IllegalStateException("Fit zoom exceeded the viewport: " + size.width + "x" + size.height);
        }
    }

    private static void verifyCanvasPaint(SimulationCanvas canvas, int width, int height) {
        canvas.setSize(width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(STALE_PIXEL_COLOR);
            graphics.fillRect(0, 0, width, height);
            canvas.paint(graphics);
        } finally {
            graphics.dispose();
        }

        int background = canvas.getBackground().getRGB();
        assertBackgroundWasCleared(image, background, width, height);
        if (!containsVisiblePixels(image, background)) {
            throw new IllegalStateException("UI canvas rendered no visible content at " + width + "x" + height);
        }
    }

    private static void assertBackgroundWasCleared(BufferedImage image, int background, int width, int height) {
        int[][] samples = {
                {0, 0},
                {width - 1, 0},
                {0, height - 1},
                {width - 1, height - 1},
                {width / 2, 0},
                {width / 2, height - 1},
                {0, height / 2},
                {width - 1, height / 2}
        };
        for (int[] sample : samples) {
            if (image.getRGB(sample[0], sample[1]) != background) {
                throw new IllegalStateException("UI canvas left stale pixels at "
                        + sample[0] + "," + sample[1] + " for " + width + "x" + height);
            }
        }
    }

    private static boolean containsVisiblePixels(BufferedImage image, int background) {
        for (int y = 0; y < image.getHeight(); y += 12) {
            for (int x = 0; x < image.getWidth(); x += 12) {
                if (image.getRGB(x, y) != background) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's cross-platform look and feel is a valid fallback.
        }
    }
}
