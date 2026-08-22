import javax.swing.JScrollPane;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
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
            // The policy checks construct a real workbench, whose AWT event
            // thread would keep the JVM alive; exit explicitly with a status.
            try {
                verifySession();
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
            System.exit(0);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("The desktop UI requires a graphical desktop session.");
            return;
        }
        configureLookAndFeel(SimulatorWorkbench.resolvedTheme());
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
        verifyTerminalFlagAndTerrainRejection();
        verifyScenarioRoundTrip();
        verifyCheckpointEditing();
        verifyRoutePointOverlap();
        verifyCombatStateInjection();
        verifyInjectionBelowFrontier();
        verifyInjectionEditSeekOrdering();
        verifyMultiUnit();
        verifyUndoRedo();
        verifyScenarioCodecRejection();
        verifyDecimalScenarioCodec();
        verifyTimeParsingEdges();
        verifyTraceCsvExport();
        verifyCheckpointArgumentValidation();
        verifyUnitSelectionSemantics();
        verifyBindDisplayState();
        verifyCheckpointRowFormat();
        verifyShortcutGuard();
        verifyRejectionFlash(after);
        verifyDisplayYUp();
        verifyTerrainAlignment(after);
        verifyVectorDisplayFlip();
        verifyTheme(after);
        verifyCoordinateLabels(after);
        verifyWorkbenchPolicies();
        System.out.println("UI session verification passed.");
    }

    /**
     * World-space vector arrows follow the y-up display: a positive world-y
     * velocity must point toward smaller canvas y (upward on screen).
     */
    private static void verifyVectorDisplayFlip() {
        UiSnapshot snapshot = new UiSnapshot(8, 3, java.util.Collections.nCopies(24, UiTerrain.OPEN),
                new UiPoint(0.5f, 1.5f), new UiPoint(7.5f, 1.5f), List.of(),
                UiMovementMode.GROUND, 1f, true, 3, "移动", 0, false, false, false,
                new UiPoint(4.5f, 1.5f), new UiPoint(4.5f, 1.5f), new UiPoint(0f, 1f),
                UiPoint.ZERO, UiPoint.ZERO, new UiCell(4, 1), null, null, false, "", 0f,
                List.of(), false);
        try {
            SwingUtilities.invokeAndWait(() -> {
                SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
                canvas.setSnapshot(snapshot);
                canvas.setViewportSize(800, 560);
                canvas.setZoom(canvas.fitZoom());
                canvas.setSize(canvas.getPreferredSize());
                BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    canvas.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                Point center = canvas.canvasPointForWorld(4.5d, 1.5d);
                if (!isVectorRed(image, center.x, center.y - 30)) {
                    throw new IllegalStateException(
                            "Velocity arrow with positive world-y must point up on screen");
                }
                if (isVectorRed(image, center.x, center.y + 30)) {
                    throw new IllegalStateException(
                            "Velocity arrow leaked below the unit: the y-up flip was not applied");
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while verifying the vector display", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Vector display verification failed", exception.getCause());
        }
    }

    private static boolean isVectorRed(BufferedImage image, int x, int y) {
        // Sample a small window to tolerate anti-aliased strokes.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                int sx = x + dx;
                int sy = y + dy;
                if (sx < 0 || sy < 0 || sx >= image.getWidth() || sy >= image.getHeight()) {
                    continue;
                }
                Color pixel = new Color(image.getRGB(sx, sy), true);
                if (pixel.getAlpha() > 120 && pixel.getRed() > 150
                        && pixel.getGreen() < 110 && pixel.getBlue() < 110) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The dark theme must render a visibly darker canvas than the light theme. */
    private static void verifyTheme(UiSnapshot snapshot) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
                canvas.setSnapshot(snapshot);
                canvas.setViewportSize(800, 560);
                canvas.setZoom(canvas.fitZoom());
                canvas.setSize(canvas.getPreferredSize());
                canvas.setTheme(UiTheme.DARK);
                int darkBg = paintCanvas(canvas).getRGB(2, 2);
                canvas.setTheme(UiTheme.LIGHT);
                int lightBg = paintCanvas(canvas).getRGB(2, 2);
                if (luminance(darkBg) >= luminance(lightBg)) {
                    throw new IllegalStateException(
                            "Dark canvas background is not darker than the light one");
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while verifying themes", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Theme verification failed", exception.getCause());
        }
    }

    private static BufferedImage paintCanvas(SimulationCanvas canvas) {
        BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            canvas.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * The coordinate toggle must paint labels into each cell's bottom-right
     * corner only, and must stay silent when cells are too small to read.
     */
    private static void verifyCoordinateLabels(UiSnapshot snapshot) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
                canvas.setSnapshot(snapshot);
                canvas.setViewportSize(800, 560);
                canvas.setZoom(40d);
                canvas.setSize(canvas.getPreferredSize());
                canvas.setShowCoordinates(false);
                BufferedImage off = paintCanvas(canvas);
                canvas.setShowCoordinates(true);
                BufferedImage on = paintCanvas(canvas);
                // Probe cell (1,1): the label must touch only its bottom-right quarter.
                Point center = canvas.canvasPointForWorld(1.5d, 1.5d);
                int size = 40;
                int left = center.x - size / 2;
                int top = center.y - size / 2;
                int changedBottomRight = 0;
                int changedTopLeft = 0;
                for (int y = top; y < top + size; y++) {
                    for (int x = left; x < left + size; x++) {
                        if (on.getRGB(x, y) == off.getRGB(x, y)) {
                            continue;
                        }
                        if (x >= left + size / 2 && y >= top + size / 2) {
                            changedBottomRight++;
                        } else if (x < left + size / 2 && y < top + size / 2) {
                            changedTopLeft++;
                        }
                    }
                }
                if (changedBottomRight == 0) {
                    throw new IllegalStateException("Coordinate toggle painted no label pixels");
                }
                if (changedTopLeft != 0) {
                    throw new IllegalStateException(
                            "Coordinate label leaked outside the bottom-right corner");
                }
                // Tiny cells must suppress labels entirely.
                canvas.setZoom(1d);
                canvas.setSize(canvas.getPreferredSize());
                canvas.setShowCoordinates(false);
                BufferedImage tinyOff = paintCanvas(canvas);
                canvas.setShowCoordinates(true);
                BufferedImage tinyOn = paintCanvas(canvas);
                for (int y = 0; y < tinyOff.getHeight(); y++) {
                    for (int x = 0; x < tinyOff.getWidth(); x++) {
                        if (tinyOn.getRGB(x, y) != tinyOff.getRGB(x, y)) {
                            throw new IllegalStateException(
                                    "Coordinate labels were painted on unreadably small cells");
                        }
                    }
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while verifying coordinate labels", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Coordinate label verification failed", exception.getCause());
        }
    }

    private static double luminance(int rgb) {
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    /**
     * Frame-level policies need a real workbench; skip where no desktop
     * session exists, since a JFrame cannot be constructed headless.
     */
    private static void verifyWorkbenchPolicies() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> {
                SimulatorWorkbench workbench = new SimulatorWorkbench();
                if (!workbench.verifyListFocusPolicy()) {
                    throw new IllegalStateException(
                            "Selection lists must stay unfocusable so Space keeps play/pause");
                }
                if (!workbench.verifyImportStaysInSpinnerRange()) {
                    throw new IllegalStateException(
                            "Imported values must stay inside the editor spinner ranges");
                }
                if (!workbench.verifySidebarFits()) {
                    throw new IllegalStateException(
                            "A wide spinner maximum clipped the sidebar horizontally");
                }
                if (!workbench.verifyThemeToggleConsistency()) {
                    throw new IllegalStateException(
                            "Toggling the theme left controls and chrome in different themes");
                }
                if (!workbench.verifyCheckpointSelectionSurvivesRefresh()) {
                    throw new IllegalStateException(
                            "A playback refresh stole the checkpoint list selection");
                }
                if (!workbench.verifyRoutePointRejectionFeedback()) {
                    throw new IllegalStateException(
                            "Refused spawn/endpoint placement lacked feedback or moved the point");
                }
                if (!workbench.verifyRoutePointEditorVisibility()) {
                    throw new IllegalStateException(
                            "Spawn/endpoint numeric rows did not follow the selected tool");
                }
                if (!workbench.verifyDecimalCheckpointFlow()) {
                    throw new IllegalStateException(
                            "Decimal checkpoint coordinates changed across canvas and codec");
                }
                if (!workbench.verifyCoordinateSpinnerAcceptsDecimal()) {
                    throw new IllegalStateException(
                            "Coordinate spinner rejected or mangled a 4-decimal value");
                }
                if (!workbench.verifyComponentIds()) {
                    throw new IllegalStateException(
                            "Interactive controls lack unique inspector IDs");
                }
                if (!workbench.verifyCheckpointAutoSelected()) {
                    throw new IllegalStateException(
                            "Checkpoint list did not keep a selected row with echoed values");
                }
                if (!workbench.verifySpinnerTypingNormalization()) {
                    throw new IllegalStateException(
                            "Spinner typing normalized IME text or was rewritten mid-edit");
                }
                if (!workbench.verifyUpdateCommitsPendingEditorText()) {
                    throw new IllegalStateException(
                            "Checkpoint update ignored text that was typed but never committed");
                }
                if (!workbench.verifyUpdateWithoutSelectionFlashesList()) {
                    throw new IllegalStateException(
                            "Rejected checkpoint update did not flash the list or explain itself");
                }
                if (!workbench.verifyShortcutActions()) {
                    throw new IllegalStateException(
                            "Delete/Esc shortcuts did not act on the session");
                }
                if (!workbench.verifyEnterUpdatesCheckpoint()) {
                    throw new IllegalStateException(
                            "Enter in a checkpoint field did not update the selected row");
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while verifying workbench policies", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Workbench policy verification failed", exception.getCause());
        }
    }

    private static void verifyExactSeek() {
        SimulationSession seekSession = new SimulationSession();
        UiSnapshot expected = null;
        for (int frame = 0; frame <= 18; frame++) {
            expected = frame == 0 ? seekSession.snapshot() : seekSession.tick();
        }
        SimulationSession replay = new SimulationSession();
        UiFrame actual = replay.seekFrame(18);
        assertFloatBits(expected.entityPosition(), actual.units().get(0).entityPosition(), "entityPosition");
        assertFloatBits(expected.cursorPosition(), actual.units().get(0).cursorPosition(), "cursorPosition");
        assertFloatBits(expected.inertiaVelocity(), actual.units().get(0).inertiaVelocity(), "inertiaVelocity");
        if (actual.frame() != 18 || !SimulationSession.formatFrameTime(18).equals("18 / 30 s")) {
            throw new IllegalStateException("Exact frame seek/time display failed");
        }
        List<UiFrame> states = replay.generatedStates();
        if (states.size() != 19 || states.get(0).frame() != 0 || states.get(18).frame() != 18) {
            throw new IllegalStateException("Timeline does not contain S[0]..S[n]");
        }
        UiFrame rewind = replay.seekFrame(3);
        if (rewind.frame() != 3 || replay.generatedStates().size() != 19
                || replay.generatedStateAtFrame(18) == null) {
            throw new IllegalStateException("Backward seek discarded confirmed timeline states");
        }
    }

    private static void verifyRoutePointOverlap() {
        // Demo: spawn (1.5,1.5), endpoint (10.5,6.5), checkpoints (5.5,1.5) (5.5,5.5).
        SimulationSession session = new SimulationSession();
        expectRejection(() -> session.placeSpawn(new UiPoint(10.5f, 6.5f)), "spawn on endpoint");
        expectRejection(() -> session.placeSpawn(new UiPoint(5.5f, 1.5f)), "spawn on checkpoint");
        expectRejection(() -> session.placeEndpoint(new UiPoint(1.5f, 1.5f)), "endpoint on spawn");
        expectRejection(() -> session.addCheckpoint(UiCheckpoint.move(new UiPoint(1.5f, 1.5f))),
                "checkpoint on spawn");
        expectRejection(() -> session.addCheckpoint(UiCheckpoint.move(new UiPoint(10.5f, 6.5f))),
                "checkpoint on endpoint");
        // Overlap is judged by the cell a point falls in: any decimal inside
        // the occupied cell is refused, the neighbouring cell is accepted.
        expectRejection(() -> session.placeSpawn(new UiPoint(5.9f, 1.2f)),
                "decimal spawn inside checkpoint cell");
        session.placeSpawn(new UiPoint(6.2f, 1.8f));
        session.placeSpawn(new UiPoint(1.5f, 1.5f)); // restore the demo spawn
        // A free position is still accepted.
        session.addCheckpoint(UiCheckpoint.move(new UiPoint(2.5f, 2.5f)));
        // An exact decimal position survives: the route target is the point itself.
        UiSnapshot snapshot = session.snapshot();
        if (Math.abs(snapshot.checkpoints().get(2).point().x() - 2.5f) > 0f) {
            throw new IllegalStateException("Decimal checkpoint coordinate was not preserved");
        }
    }

    private static void expectRejection(Runnable action, String what) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException("Route-point overlap was not rejected: " + what);
    }

    private static void verifyTimeParsing() {
        if (SimulationSession.parseTimeToFrame("16") != 16
                || SimulationSession.parseTimeToFrame("16/30") != 16
                || SimulationSession.parseTimeToFrame("0.5") != 15
                || SimulationSession.parseTimeToFrame("1.2") != 36
                // Full-width digits/separators from a Chinese IME normalize to ASCII.
                || SimulationSession.parseTimeToFrame("１６") != 16
                || SimulationSession.parseTimeToFrame("１６／３０") != 16
                || SimulationSession.parseTimeToFrame("０．５") != 15) {
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
                SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
                canvas.setSnapshot(snapshot);
                canvas.setViewportSize(800, 560);
                if (canvas.fitZoom() <= 0d) {
                    throw new IllegalStateException("Fit zoom was not available for an 800x560 viewport");
                }
                canvas.setZoom(canvas.fitZoom());
                assertFitsViewport(canvas, 800, 560);
                if (Math.abs(canvas.zoomPercent() - 100d) > 0.0001d) {
                    throw new IllegalStateException("Fit zoom was not reported as 100%");
                }
                canvas.setZoom(canvas.maximumZoom());
                if (Math.abs(canvas.zoomPercent() - 30_000d) > 0.0001d) {
                    throw new IllegalStateException("Maximum zoom was not 30000%");
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
                verifyBrowsePan(snapshot);
                verifyNonPrimaryButtonsDoNotEdit(snapshot);
                verifyPanCameraTranslate(snapshot);
                verifyPanBlitKeepsCanvasClean(snapshot);
                verifyTrajectoryTooltip(canvas);
                verifyHoverInvalidation(snapshot);
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

    private static void verifyBrowsePan(UiSnapshot snapshot) {
        int[] editCount = {0};
        SimulationCanvas canvas = new SimulationCanvas((cell, point) -> editCount[0]++);
        canvas.setSnapshot(snapshot);
        canvas.setViewportSize(320, 240);
        canvas.setZoom(100d);
        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.setSize(320, 240);
        scrollPane.doLayout();
        javax.swing.JViewport viewport = scrollPane.getViewport();
        viewport.setExtentSize(new Dimension(300, 220));
        viewport.setViewSize(canvas.getPreferredSize());
        viewport.setViewPosition(new Point(200, 180));
        Point initialView = viewport.getViewPosition();

        canvas.setBrowseMode(true);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L, 0,
                200, 180, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0L,
                MouseEvent.BUTTON1_DOWN_MASK, 160, 150, 0, false, MouseEvent.NOBUTTON));
        Point pannedView = viewport.getViewPosition();
        if (pannedView.x != initialView.x + 40 || pannedView.y != initialView.y + 30) {
            throw new IllegalStateException("Browse pan was not pixel-for-pixel viewport movement");
        }
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0,
                160, 150, 1, false, MouseEvent.BUTTON1));
        if (editCount[0] != 0) {
            throw new IllegalStateException("Browse pan invoked an editor action");
        }

        canvas.setBrowseMode(false);
        Point editPoint = canvas.canvasPointForWorld(1.5d, 1.5d);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L,
                MouseEvent.BUTTON1_DOWN_MASK, editPoint.x, editPoint.y, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0,
                editPoint.x, editPoint.y, 1, false, MouseEvent.BUTTON1));
        if (editCount[0] != 1) {
            throw new IllegalStateException("Leaving browse mode did not restore map editing");
        }

        // Right-button drag pans under any tool and never invokes an edit;
        // middle and left buttons no longer pan outside browse mode.
        viewport.setViewPosition(new Point(200, 180));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L,
                MouseEvent.BUTTON3_DOWN_MASK, 200, 180, 1, false, MouseEvent.BUTTON3));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0L,
                MouseEvent.BUTTON3_DOWN_MASK, 160, 150, 0, false, MouseEvent.NOBUTTON));
        Point rightPannedView = viewport.getViewPosition();
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0,
                160, 150, 1, false, MouseEvent.BUTTON3));
        if (rightPannedView.x != 240 || rightPannedView.y != 210) {
            throw new IllegalStateException("Right-button drag did not pan the viewport");
        }
        if (editCount[0] != 1) {
            throw new IllegalStateException("Right-button pan invoked an editor action");
        }

        viewport.setViewPosition(new Point(200, 180));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L,
                MouseEvent.BUTTON2_DOWN_MASK, 200, 180, 1, false, MouseEvent.BUTTON2));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0L,
                MouseEvent.BUTTON2_DOWN_MASK, 160, 150, 0, false, MouseEvent.NOBUTTON));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0,
                160, 150, 1, false, MouseEvent.BUTTON2));
        Point afterMiddle = viewport.getViewPosition();
        if (afterMiddle.x != 200 || afterMiddle.y != 180) {
            throw new IllegalStateException("Middle-button drag still pans after the rebinding");
        }
    }

    private static void verifyNonPrimaryButtonsDoNotEdit(UiSnapshot snapshot) {
        int[] editCount = {0};
        SimulationCanvas canvas = new SimulationCanvas((cell, point) -> editCount[0]++);
        canvas.setSnapshot(snapshot);
        Point point = canvas.canvasPointForWorld(1.5d, 1.5d);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L,
                MouseEvent.BUTTON2_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON2));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 0L,
                MouseEvent.BUTTON3_DOWN_MASK, point.x, point.y, 0, false, MouseEvent.BUTTON3));
        if (editCount[0] != 0) {
            throw new IllegalStateException("Non-primary mouse buttons invoked an editor action");
        }
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 0L,
                MouseEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1));
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0L, 0,
                point.x, point.y, 1, false, MouseEvent.BUTTON1));
        if (editCount[0] != 1) {
            throw new IllegalStateException("The primary button no longer edits after the button guard");
        }
    }

    /** Shift+click placement snaps to the cell center; plain placement is exact. */
    private static void verifyPanCameraTranslate(UiSnapshot snapshot) {
        UiPoint snapped = SimulationCanvas.snapPointToCellCenter(true,
                new UiCell(3, 2), new UiPoint(3.9f, 2.1f));
        if (snapped.x() != 3.5f || snapped.y() != 2.5f) {
            throw new IllegalStateException("Shift placement did not snap to the cell center");
        }
        UiPoint free = SimulationCanvas.snapPointToCellCenter(false,
                new UiCell(3, 2), new UiPoint(3.9f, 2.1f));
        if (free.x() != 3.9f || free.y() != 2.1f) {
            throw new IllegalStateException("Plain placement lost its exact coordinates");
        }
    }

    /**
     * A drag pan must not rebuild the static world buffer: the repaint is one
     * offscreen blit, which is what makes paused dragging smooth.
     */
    private static void verifyPanBlitKeepsCanvasClean(UiSnapshot snapshot) {
        SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
        canvas.setSnapshot(snapshot);
        canvas.setViewportSize(320, 240);
        canvas.setZoom(100d);
        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.setSize(320, 240);
        scrollPane.doLayout();
        javax.swing.JViewport viewport = scrollPane.getViewport();
        viewport.setExtentSize(new Dimension(300, 220));
        viewport.setViewSize(canvas.getPreferredSize());
        viewport.setViewPosition(new Point(200, 180));
        if (!canvas.verifyPanKeepsStaticBuffer()) {
            throw new IllegalStateException("A drag pan rebuilt the static world buffer");
        }
    }

    private static void verifyTerminalFlagAndTerrainRejection() {        SimulationSession session = new SimulationSession();
        session.newScenario(4, 3);
        if (!session.setTerrain(new UiCell(1, 0), UiTerrain.WALL)
                || !session.setTerrain(new UiCell(1, 1), UiTerrain.WALL)
                || !session.setTerrain(new UiCell(1, 2), UiTerrain.WALL)) {
            throw new IllegalStateException("Placing walls on free cells was rejected");
        }
        if (session.setTerrain(new UiCell(0, 1), UiTerrain.WALL)) {
            throw new IllegalStateException("Placing terrain on the spawn cell was accepted");
        }
        UiSnapshot blocked = session.tick();
        if (!blocked.terminal() || !"阻挡".equals(blocked.unitMode())) {
            throw new IllegalStateException("The terminal flag did not reflect the blocked mode");
        }
    }

    private static void verifyScenarioRoundTrip() {        SimulationSession original = new SimulationSession();
        String text = original.exportScenario();
        SimulationSession imported = new SimulationSession();
        imported.newScenario(6, 5);
        imported.importScenario(text);
        if (!imported.exportScenario().equals(text)) {
            throw new IllegalStateException("Scenario round trip changed the exported text");
        }
        UiSnapshot expected = null;
        UiSnapshot actual = null;
        for (int frame = 0; frame < 24; frame++) {
            expected = frame == 0 ? original.resetSimulation() : original.tick();
            actual = frame == 0 ? imported.snapshot() : imported.tick();
            assertFloatBits(expected.entityPosition(), actual.entityPosition(), "round-trip entity position");
            assertFloatBits(expected.cursorPosition(), actual.cursorPosition(), "round-trip cursor position");
            assertFloatBits(expected.inertiaVelocity(), actual.inertiaVelocity(), "round-trip inertia");
        }
        if (expected == null || expected.frame() != 23) {
            throw new IllegalStateException("Round-trip replay did not advance through frame 23");
        }
        String csv = original.exportTraceCsv();
        String[] rows = csv.split("\\R");
        if (!rows[0].startsWith("unit,") || rows.length != original.generatedStates().size() + 1
                || !rows[rows.length - 1].startsWith("0,23,")) {
            throw new IllegalStateException("Trace CSV did not contain one row per generated frame");
        }
        try {
            new SimulationSession().importScenario(text.replace("movement GROUND", "movement HOVER"));
            throw new IllegalStateException("Import accepted an unknown movement mode");
        } catch (IllegalArgumentException expectedError) {
            if (!expectedError.getMessage().contains("Unknown movement mode")) {
                throw new IllegalStateException("Unknown movement mode error was not reported");
            }
        }
        try {
            new SimulationSession().importScenario(text.replace("map 12 8", "map 1 1"));
            throw new IllegalStateException("Import accepted an undersized map");
        } catch (IllegalArgumentException expectedError) {
            if (!expectedError.getMessage().contains("between 2")) {
                throw new IllegalStateException("Undersized map error was not reported");
            }
        }
    }

    /**
     * Decimal route points round-trip bit-exactly through the codec, and
     * legacy integer-cell files load as cell centers.
     */
    private static void verifyDecimalScenarioCodec() {
        SimulationSession session = new SimulationSession();
        session.newScenario(8, 3);
        session.placeSpawn(new UiPoint(0.5f, 1.1222f));
        session.placeEndpoint(new UiPoint(7.25f, 1.5f));
        session.addCheckpoint(UiCheckpoint.move(new UiPoint(3.3f, 2.7f)));
        String text = session.exportScenario();
        if (!text.contains("spawn 0.5 1.1222") || !text.contains("endpoint 7.25 1.5")
                || !text.contains("checkpoint MOVE 3.3 2.7")) {
            throw new IllegalStateException("Decimal coordinates were not exported exactly: " + text);
        }
        SimulationSession imported = new SimulationSession();
        imported.importScenario(text);
        UiSnapshot snapshot = imported.snapshot();
        if (snapshot.spawn().x() != 0.5f || snapshot.spawn().y() != 1.1222f
                || snapshot.endpoint().x() != 7.25f
                || snapshot.checkpoints().get(0).point().x() != 3.3f
                || snapshot.checkpoints().get(0).point().y() != 2.7f) {
            throw new IllegalStateException("Decimal coordinates changed across the round trip");
        }
        if (!imported.exportScenario().equals(text)) {
            throw new IllegalStateException("Decimal export was not stable across a round trip");
        }

        // Legacy v2/v3 text: integer cells become their centers.
        String legacy = "# arknights pathfinding scenario v3\n"
                + "map 8 3\n"
                + "unit 1\n"
                + "spawn 1 1\n"
                + "endpoint 6 1\n"
                + "movement GROUND\n"
                + "speed 1.0\n"
                + "diagonal true\n"
                + "checkpoint MOVE 3 1\n";
        SimulationSession upgraded = new SimulationSession();
        upgraded.importScenario(legacy);
        UiSnapshot legacySnapshot = upgraded.snapshot();
        if (legacySnapshot.spawn().x() != 1.5f || legacySnapshot.spawn().y() != 1.5f
                || legacySnapshot.endpoint().x() != 6.5f
                || legacySnapshot.checkpoints().get(0).point().x() != 3.5f) {
            throw new IllegalStateException("Legacy cell coordinates did not load as centers");
        }
        // The header marks the decimal format; without it integers stay cells.
        String headerless = legacy.replace("# arknights pathfinding scenario v3\n", "");
        SimulationSession headerlessImport = new SimulationSession();
        headerlessImport.importScenario(headerless);
        if (headerlessImport.snapshot().spawn().x() != 1.5f) {
            throw new IllegalStateException("Headerless file was not read as legacy cells");
        }
    }

    private static void verifyCheckpointEditing() {
        SimulationSession waitSession = new SimulationSession();
        waitSession.newScenario(6, 2);
        waitSession.addCheckpoint(UiCheckpoint.waitForSeconds(0.1f));
        for (int frame = 0; frame < 3; frame++) {
            waitSession.tick();
        }
        if (waitSession.snapshot().activeCheckpoint() != 0) {
            throw new IllegalStateException("Wait checkpoint completed before its duration");
        }
        waitSession.tick();
        if (waitSession.snapshot().activeCheckpoint() != 1) {
            throw new IllegalStateException("Wait checkpoint did not advance after its duration");
        }

        SimulationSession patrolSession = new SimulationSession();
        patrolSession.newScenario(8, 3);
        patrolSession.addCheckpoint(UiCheckpoint.move(new UiPoint(2.5f, 1.5f)));
        patrolSession.addCheckpoint(UiCheckpoint.patrolMove(new UiPoint(5.5f, 1.5f)));
        for (int frame = 0; frame < 600; frame++) {
            patrolSession.tick();
            if (patrolSession.snapshot().activeCheckpoint() == 0 && frame > 200) {
                break;
            }
        }
        if (patrolSession.snapshot().activeCheckpoint() != 0 || patrolSession.isTerminal()) {
            throw new IllegalStateException("Terminal patrol loop did not return to checkpoint zero");
        }

        SimulationSession portalSession = new SimulationSession();
        portalSession.newScenario(8, 3);
        portalSession.addCheckpoint(UiCheckpoint.appearAt(new UiPoint(6.5f, 1.5f)));
        portalSession.insertCheckpointBefore(0, UiCheckpoint.disappear());
        UiSnapshot portalFrame = portalSession.tick();
        if (!portalFrame.transition().contains("APPEAR_AT_POS")
                || !portalFrame.trajectoryBreak()
                || Math.abs(portalFrame.entityPosition().x() - 6.5f) > 0.001f) {
            throw new IllegalStateException("Portal checkpoint flow did not relocate and break the trajectory");
        }
        String portalText = portalSession.exportScenario();
        SimulationSession portalImported = new SimulationSession();
        portalImported.importScenario(portalText);
        if (!portalImported.exportScenario().equals(portalText)) {
            throw new IllegalStateException("Typed checkpoint round trip changed the exported text");
        }

        try {
            new SimulationSession().addCheckpoint(UiCheckpoint.disappear());
            throw new IllegalStateException("Adding a terminal DISAPPEAR was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("must be followed by")) {
                throw new IllegalStateException("Terminal DISAPPEAR error was not reported");
            }
        }
        try {
            portalSession.removeCheckpoint(1);
            throw new IllegalStateException("Removing the portal appearance was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("must be followed by")) {
                throw new IllegalStateException("Portal removal error was not reported");
            }
        }
    }

    private static void verifyCombatStateInjection() {
        SimulationSession stunSession = new SimulationSession();
        stunSession.applyStun(0.2f);
        UiSnapshot stunned = stunSession.tick();
        if (!"眩晕".equals(stunned.unitMode())) {
            throw new IllegalStateException("Stun event did not take effect on the next tick");
        }
        UiPoint held = stunned.entityPosition();
        for (int frame = 0; frame < 5; frame++) {
            stunSession.tick();
        }
        UiSnapshot stillStunned = stunSession.snapshot();
        if (!"眩晕".equals(stillStunned.unitMode()) || !held.equals(stillStunned.entityPosition())) {
            throw new IllegalStateException("Stunned unit moved or left the mode early");
        }
        stunSession.tick();
        if (!"移动".equals(stunSession.snapshot().unitMode())) {
            throw new IllegalStateException("Stun did not expire after its duration");
        }

        SimulationSession pushSession = new SimulationSession();
        pushSession.newScenario(8, 3);
        pushSession.applyDisplacement(0f, -3f, 0.15f);
        UiSnapshot pushed = pushSession.tick();
        if (!"位移".equals(pushed.unitMode())) {
            throw new IllegalStateException("Displacement event did not take effect on the next tick");
        }
        for (int frame = 0; frame < 4; frame++) {
            pushSession.tick();
        }
        UiSnapshot stillPushed = pushSession.snapshot();
        if (!"位移".equals(stillPushed.unitMode())
                || Math.abs(stillPushed.entityPosition().y() - 1.0f) > 0.001f) {
            throw new IllegalStateException("Displaced unit did not move at constant velocity");
        }
        pushSession.tick();
        if (!"移动".equals(pushSession.snapshot().unitMode())) {
            throw new IllegalStateException("Displacement did not expire into movement");
        }

        SimulationSession bindSession = new SimulationSession();
        bindSession.setUnitBound(true);
        UiSnapshot bound = bindSession.tick();
        if (!bound.bound()) {
            throw new IllegalStateException("Bind event did not take effect on the next tick");
        }
        bindSession.tick();
        if (!bindSession.snapshot().entityPosition().equals(bound.entityPosition())) {
            throw new IllegalStateException("Bound unit moved");
        }
        bindSession.setUnitBound(false);
        bindSession.tick();
        if (bindSession.snapshot().bound()
                || bindSession.snapshot().entityPosition().equals(bound.entityPosition())) {
            throw new IllegalStateException("Released unit did not resume movement");
        }

        SimulationSession replay = new SimulationSession();
        replay.applyStun(0.2f);
        replay.tick();
        replay.tick();
        replay.applyDisplacement(-2f, 0f, 0.1f);
        replay.tick();
        replay.tick();
        replay.tick();
        List<UiPoint> first = new java.util.ArrayList<>();
        for (int frame = 0; frame <= 5; frame++) {
            first.add(replay.generatedStateAtFrame(frame).units().get(0).entityPosition());
        }
        replay.seekFrame(0);
        for (int frame = 0; frame <= 5; frame++) {
            assertFloatBits(first.get(frame),
                    replay.generatedStateAtFrame(frame).units().get(0).entityPosition(),
                    "injection replay frame " + frame);
        }

        SimulationSession terminal = new SimulationSession();
        terminal.newScenario(4, 3);
        terminal.setTerrain(new UiCell(1, 0), UiTerrain.WALL);
        terminal.setTerrain(new UiCell(1, 1), UiTerrain.WALL);
        terminal.setTerrain(new UiCell(1, 2), UiTerrain.WALL);
        terminal.tick();
        try {
            terminal.applyStun(1f);
            throw new IllegalStateException("Injection after the terminal frame was accepted");
        } catch (IllegalStateException expected) {
            if (expected.getMessage() == null || !expected.getMessage().contains("终态")) {
                throw new IllegalStateException("Terminal injection error was not reported");
            }
        }
    }

    private static void verifyInjectionBelowFrontier() {
        SimulationSession session = new SimulationSession();
        for (int frame = 0; frame < 10; frame++) {
            session.tickFrame();
        }
        UiSnapshot originalSix = session.generatedStateAtFrame(6).units().get(0);
        if (!"移动".equals(originalSix.unitMode())) {
            throw new IllegalStateException("Demo unit was expected to be moving at frame 6");
        }
        session.seekFrame(2);
        session.applyStun(1f);
        if (session.generatedFrameCount() != 3) {
            throw new IllegalStateException("Injection below the frontier did not drop the invalidated tail");
        }
        while (session.canTick() && session.generatedFrameCount() <= 11) {
            session.tickFrame();
        }
        UiSnapshot branchedSix = session.seekFrame(6).units().get(0);
        if (!"眩晕".equals(branchedSix.unitMode())
                || branchedSix.entityPosition().equals(originalSix.entityPosition())) {
            throw new IllegalStateException("Seek after a below-frontier injection returned the stale run");
        }
        String csv = session.exportTraceCsv();
        boolean branchedRow = false;
        for (String row : csv.split("\n")) {
            if (row.startsWith("0,6,") && row.contains("眩晕")) {
                branchedRow = true;
            }
        }
        if (!branchedRow) {
            throw new IllegalStateException("CSV export kept a stale pre-injection frame");
        }
        List<UiPoint> branched = new java.util.ArrayList<>();
        for (int frame = 0; frame <= 10; frame++) {
            branched.add(session.generatedStateAtFrame(frame).units().get(0).entityPosition());
        }
        session.seekFrame(0);
        for (int frame = 0; frame <= 10; frame++) {
            assertFloatBits(branched.get(frame),
                    session.seekFrame(frame).units().get(0).entityPosition(),
                    "branched injection replay frame " + frame);
        }

        SimulationSession terminal = new SimulationSession();
        terminal.newScenario(4, 3);
        terminal.setTerrain(new UiCell(1, 0), UiTerrain.WALL);
        terminal.setTerrain(new UiCell(1, 1), UiTerrain.WALL);
        terminal.setTerrain(new UiCell(1, 2), UiTerrain.WALL);
        terminal.tickFrame();
        int oldTerminal = terminal.terminalFrame();
        if (oldTerminal < 0) {
            throw new IllegalStateException("Walled unit was expected to reach a terminal frame");
        }
        terminal.seekFrame(0);
        terminal.applyStun(1f);
        if (terminal.terminalFrame() >= 0) {
            throw new IllegalStateException("Stale terminal frame survived a below-frontier injection");
        }
        while (terminal.canTick() && terminal.generatedFrameCount() <= 40) {
            terminal.tickFrame();
        }
        if (!terminal.isTerminal() || terminal.terminalFrame() <= oldTerminal) {
            throw new IllegalStateException("Stale terminal frame still blocked the re-derived run");
        }
    }

    /**
     * The most complex session ordering: inject a run event, seek backward,
     * edit the scenario (which clears run events and restarts playback), then
     * seek into frames that only existed in the pre-edit timeline. Every step
     * must observe only the post-edit scenario, with no leak from the cleared
     * injection or the discarded timeline.
     */
    private static void verifyInjectionEditSeekOrdering() {
        SimulationSession session = new SimulationSession();
        for (int frame = 0; frame < 8; frame++) {
            session.tickFrame();
        }
        UiPoint preEditSix = session.generatedStateAtFrame(6).units().get(0).entityPosition();

        // Inject below the frontier, then seek backward before the edit.
        session.seekFrame(2);
        session.applyStun(1f);
        if (session.generatedFrameCount() != 3) {
            throw new IllegalStateException("Setup: below-frontier injection did not truncate");
        }
        long revisionBeforeEdit = session.scenarioRevision();

        // The scenario edit clears run events and restarts the timeline.
        session.setTerrain(new UiCell(4, 4), UiTerrain.WALL);
        if (session.scenarioRevision() == revisionBeforeEdit || session.snapshot().frame() != 0) {
            throw new IllegalStateException("Scenario edit did not restart playback");
        }
        if (session.generatedFrameCount() != 1) {
            throw new IllegalStateException("Edit kept pre-edit timeline frames: "
                    + session.generatedFrameCount());
        }

        // The cleared injection must not leak: regenerate past the old point.
        for (int frame = 0; frame < 8 && session.canTick(); frame++) {
            session.tickFrame();
        }
        UiSnapshot six = session.seekFrame(6).units().get(0);
        if ("眩晕".equals(six.unitMode())) {
            throw new IllegalStateException("Cleared stun leaked into the post-edit run");
        }
        if (!"移动".equals(six.unitMode()) && !session.isTerminal()) {
            throw new IllegalStateException("Unexpected mode after edit: " + six.unitMode());
        }

        // Determinism across the whole sequence: replay from S[0] matches.
        List<UiPoint> postEdit = new java.util.ArrayList<>();
        for (int frame = 0; frame < session.generatedFrameCount(); frame++) {
            postEdit.add(session.generatedStateAtFrame(frame).units().get(0).entityPosition());
        }
        session.seekFrame(0);
        for (int frame = 0; frame < postEdit.size(); frame++) {
            assertFloatBits(postEdit.get(frame),
                    session.seekFrame(frame).units().get(0).entityPosition(),
                    "post-edit replay frame " + frame);
        }

        // Frame 6 exists in the post-edit timeline and was re-derived under the
        // edited scenario (wall present), not served from the discarded pre-edit
        // timeline (which had the stun and no wall). Its mode proves which run
        // produced it: the pre-edit branch was stunned, the post-edit one is not.
        UiFrame sixFrame = session.generatedStateAtFrame(6);
        if (sixFrame == null) {
            throw new IllegalStateException("Post-edit timeline did not regenerate frame 6");
        }
        UiSnapshot postEditSix = sixFrame.units().get(0);
        if ("眩晕".equals(postEditSix.unitMode())) {
            throw new IllegalStateException("Post-edit frame 6 came from the stale pre-edit run");
        }
        if (postEditSix.entityPosition().equals(preEditSix) && session.generatedFrameCount() > 6
                && !"移动".equals(postEditSix.unitMode())) {
            throw new IllegalStateException("Post-edit frame 6 is in an impossible state");
        }

        // Undoing the edit restores the pre-edit scenario but not its run
        // events; the restored timeline must also restart cleanly from S[0].
        if (!session.undo() || session.generatedFrameCount() != 1 || session.snapshot().frame() != 0) {
            throw new IllegalStateException("Undo after the ordering sequence did not restart cleanly");
        }
        UiSnapshot ticked = session.tick();
        if (!"移动".equals(ticked.unitMode())) {
            throw new IllegalStateException("Undo leaked a combat injection into the restored scenario");
        }
    }

    private static void verifyMultiUnit() {
        SimulationSession session = new SimulationSession();
        session.newScenario(8, 3);
        session.addDraft();
        if (session.unitCount() != 2 || session.selectedDraftIndex() != 1) {
            throw new IllegalStateException("Adding a draft did not create and select a second unit");
        }
        session.selectDraft(1);
        session.placeEndpoint(new UiPoint(6.5f, 2.5f));
        UiFrame frame = session.tickFrame();
        if (frame.units().size() != 2 || frame.frame() != 1) {
            throw new IllegalStateException("Stage frame did not carry every unit");
        }

        List<List<UiPoint>> firstRun = new java.util.ArrayList<>();
        for (int step = 0; step < 6; step++) {
            UiFrame next = session.tickFrame();
            List<UiPoint> positions = new java.util.ArrayList<>();
            for (UiSnapshot unit : next.units()) {
                positions.add(unit.entityPosition());
            }
            firstRun.add(positions);
        }
        session.seekFrame(0);
        int step = 0;
        for (int tick = 0; tick < 7; tick++) {
            UiFrame next = session.tickFrame();
            if (next.frame() < 2) {
                continue;
            }
            for (int unit = 0; unit < 2; unit++) {
                assertFloatBits(firstRun.get(step).get(unit), next.units().get(unit).entityPosition(),
                        "multi-unit replay unit " + unit + " step " + step);
            }
            step++;
        }

        String text = session.exportScenario();
        SimulationSession imported = new SimulationSession();
        imported.importScenario(text);
        if (imported.unitCount() != 2 || !imported.exportScenario().equals(text)) {
            throw new IllegalStateException("Multi-unit scenario round trip changed the text");
        }

        try {
            session.removeDraft(0);
            session.removeDraft(0);
            throw new IllegalStateException("Removing the last unit was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("至少保留")) {
                throw new IllegalStateException("Last-unit removal error was not reported");
            }
        }

        SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
        canvas.setSnapshot(frame.units().get(0));
        canvas.setUnits(frame.units());
        verifyCanvasPaint(canvas, 800, 560);
    }

    private static void verifyUndoRedo() {
        SimulationSession session = new SimulationSession();
        if (session.canUndo() || session.canRedo() || session.undo() || session.redo()) {
            throw new IllegalStateException("A fresh session must start with empty undo history");
        }
        String pristine = session.exportScenario();

        session.setTerrain(new UiCell(3, 3), UiTerrain.WALL);
        session.placeSpawn(new UiPoint(2.5f, 2.5f));
        session.addCheckpoint(UiCheckpoint.waitForSeconds(1.5f));
        session.addDraft();
        session.setAllowDiagonalMove(false);
        for (int edit = 0; edit < 5; edit++) {
            if (!session.undo()) {
                throw new IllegalStateException("Undo stopped early at step " + edit);
            }
        }
        if (!session.exportScenario().equals(pristine)) {
            throw new IllegalStateException("Undoing every edit did not restore the exported scenario");
        }
        if (session.canUndo()) {
            throw new IllegalStateException("Undo stack outlived its edits");
        }
        for (int edit = 0; edit < 5; edit++) {
            if (!session.redo()) {
                throw new IllegalStateException("Redo stopped early at step " + edit);
            }
        }
        if (session.unitCount() != 2 || session.snapshot().terrainAt(new UiCell(3, 3)) != UiTerrain.WALL
                || session.snapshot().allowDiagonalMove()) {
            throw new IllegalStateException("Redo did not restore the edited scenario");
        }

        // No-op and rejected edits must not consume undo slots.
        session.setTerrain(new UiCell(3, 3), UiTerrain.WALL);
        try {
            session.addCheckpoint(UiCheckpoint.disappear());
            throw new IllegalStateException("A trailing DISAPPEAR checkpoint was accepted");
        } catch (IllegalArgumentException expected) {
        }
        int applied = 0;
        while (session.undo()) {
            applied++;
            if (applied > 20) {
                throw new IllegalStateException("Undo history grew without bound");
            }
        }
        if (applied != 5 || !session.exportScenario().equals(pristine)) {
            throw new IllegalStateException("No-op or rejected edits consumed undo slots");
        }
        for (int edit = 0; edit < 5; edit++) {
            session.redo();
        }

        session.undo();
        session.setTerrain(new UiCell(4, 4), UiTerrain.PIT);
        if (session.canRedo() || session.redo()) {
            throw new IllegalStateException("A fresh edit did not clear the redo branch");
        }

        String beforeReset = session.exportScenario();
        session.newScenario(6, 4);
        if (session.mapWidth() != 6 || session.mapHeight() != 4 || !session.canUndo()) {
            throw new IllegalStateException("newScenario was not recorded for undo");
        }
        session.loadDemoScenario();
        if (!session.undo() || session.mapWidth() != 6 || session.mapHeight() != 4) {
            throw new IllegalStateException("Undo did not restore the scenario replaced by the demo");
        }
        if (!session.undo() || !session.exportScenario().equals(beforeReset)) {
            throw new IllegalStateException("Undo did not restore the scenario replaced by newScenario");
        }

        String edited = session.exportScenario();
        session.importScenario(pristine);
        if (!session.exportScenario().equals(pristine)) {
            throw new IllegalStateException("Import did not apply");
        }
        if (!session.undo() || !session.exportScenario().equals(edited)) {
            throw new IllegalStateException("Undo did not restore the scenario replaced by import");
        }

        // Combat injections and playback progress are run state, not scenario state.
        session.tickFrame();
        session.tickFrame();
        session.applyStun(0.2f);
        session.setTerrain(new UiCell(6, 6), UiTerrain.WALL);
        if (session.snapshot().frame() != 0 || session.snapshot().terrainAt(new UiCell(6, 6)) != UiTerrain.WALL) {
            throw new IllegalStateException("A scenario edit did not restart playback");
        }
        if (!session.undo() || session.snapshot().terrainAt(new UiCell(6, 6)) != UiTerrain.OPEN) {
            throw new IllegalStateException("Undo did not revert the terrain edit");
        }
        UiSnapshot afterUndoTick = session.tick();
        if (!"移动".equals(afterUndoTick.unitMode())) {
            throw new IllegalStateException("Undo leaked a cleared combat injection into the new run");
        }
        if (!session.redo() || session.snapshot().frame() != 0) {
            throw new IllegalStateException("Redo must restart playback from S[0]");
        }

        // Unit selection is captured with the scenario state.
        session.addDraft();
        session.selectDraft(0);
        session.setTerrain(new UiCell(9, 1), UiTerrain.BOX);
        if (!session.undo() || session.selectedDraftIndex() != 0 || session.unitCount() != 3) {
            throw new IllegalStateException("Undo did not restore the selected unit");
        }
        if (!session.undo() || session.unitCount() != 2 || session.selectedDraftIndex() != 1) {
            throw new IllegalStateException("Undo did not restore the unit list");
        }

        // History is capped at 100 entries.
        SimulationSession capped = new SimulationSession();
        for (int step = 0; step < 105; step++) {
            capped.setTerrain(new UiCell(3, 3), step % 2 == 0 ? UiTerrain.WALL : UiTerrain.OPEN);
        }
        int available = 0;
        while (capped.undo()) {
            available++;
        }
        if (available != 100) {
            throw new IllegalStateException("Undo history was not capped, got " + available);
        }
    }

    /** Every rejection path of the scenario format must report its line number. */
    private static void verifyScenarioCodecRejection() {
        String header = "map 4 3\n";
        String unit = "spawn 0 1\nendpoint 3 1\nmovement GROUND\nspeed 1.0\ndiagonal true\n";
        String[][] cases = {
                {"spawn 0 1\n" + unit.substring(0, 0), "map"},
                {header + "map 4 3\n" + unit, "twice"},
                {header + "unit 2\n" + unit, "sequential"},
                {header + "unit 1\nspawn 9 1\nendpoint 3 1\nmovement GROUND\nspeed 1.0\ndiagonal true\n",
                        "outside the map"},
                {header + "spawn 0 1\nspawn 1 1\nendpoint 3 1\nmovement GROUND\nspeed 1.0\ndiagonal true\n",
                        "twice"},
                {header + "spawn 0 1\nendpoint 3 1\nmovement GROUND\nspeed 1.0\ndiagonal true\n"
                        + "checkpoint MOVE 1 1\ncheckpoint MOVE 1 1\n", "Duplicate checkpoint"},
                {header + unit + "checkpoint WAIT_FOR_SECONDS -1\n", "non-negative"},
                {header + unit + "checkpoint WAIT_BOSSRUSH_WAVE -2\n", "non-negative"},
                {header + "spawn 0 1\nendpoint 3 1\n", "movement, speed, and diagonal"},
                {header + unit + "hover 1\n", "Unknown directive"},
                {header + unit + "checkpoint MOVE 1\n", "Expected"},
                {header + "spawn 0 1\nendpoint 3 1\nmovement GROUND\nspeed nope\ndiagonal true\n",
                        "not a number"},
                {header + "spawn 0 1\nendpoint 3 1\nmovement GROUND\nspeed 0.05\ndiagonal true\n",
                        "at least 0.1"},
                {header + unit + "terrain 2 2 GRASS\n", "Unknown terrain"},
                {header + unit + "checkpoint MADE_UP 1 1\n", "Unknown checkpoint type"},
                {header + "diagonal yes\nspawn 0 1\nendpoint 3 1\nmovement GROUND\nspeed 1.0\n",
                        "true or false"},
        };
        for (String[] testCase : cases) {
            String text = testCase[0];
            String messagePart = testCase[1];
            try {
                new SimulationSession().importScenario(text);
                throw new IllegalStateException("Import accepted malformed text expecting '" + messagePart + "'");
            } catch (IllegalArgumentException expected) {
                if (expected.getMessage() == null || !expected.getMessage().contains(messagePart)) {
                    throw new IllegalStateException("Rejection '" + messagePart
                            + "' produced the wrong message: " + expected.getMessage());
                }
            }
        }

        // A rejected import must leave the current scenario untouched.
        SimulationSession untouched = new SimulationSession();
        String before = untouched.exportScenario();
        try {
            untouched.importScenario(header + "bogus");
        } catch (IllegalArgumentException expected) {
            // Expected: the import is rejected.
        }
        if (!untouched.exportScenario().equals(before)) {
            throw new IllegalStateException("A rejected import mutated the current scenario");
        }

        // Files saved with a UTF-8 BOM parse exactly like their plain form.
        SimulationSession demo = new SimulationSession();
        String plain = demo.exportScenario();
        SimulationSession bom = new SimulationSession();
        bom.newScenario(6, 5);
        bom.importScenario("﻿" + plain);
        if (!bom.exportScenario().equals(plain)) {
            throw new IllegalStateException("A BOM-prefixed scenario did not round trip");
        }

        // A terrain cell on a route point is rejected before any state changes.
        String conflict = plain.replace("terrain 7 3 BOX", "terrain 1 1 BOX");
        if (conflict.equals(plain)) {
            throw new IllegalStateException("Demo scenario export changed; update the conflict fixture");
        }
        try {
            new SimulationSession().importScenario(conflict);
            throw new IllegalStateException("Import accepted terrain on the spawn cell");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("conflicts with a route point")) {
                throw new IllegalStateException("Terrain/route conflict error was not reported");
            }
        }
    }

    private static void verifyTimeParsingEdges() {
        if (SimulationSession.parseTimeToFrame("  16  ") != 16
                || SimulationSession.parseTimeToFrame(" 1/30 ") != 1
                || SimulationSession.parseTimeToFrame("-0") != 0
                || SimulationSession.parseTimeToFrame("0") != 0) {
            throw new IllegalStateException("Whitespace and signed-zero time forms parsed incorrectly");
        }
        expectParseFailure(null, "required");
        expectParseFailure("", "required");
        expectParseFailure("   ", "required");
        expectParseFailure("-1", "non-negative");
        expectParseFailure("1/7", "n/30");
        expectParseFailure("abc", "Invalid time");
        expectParseFailure("99999999999999999999", "too large");
        try {
            SimulationSession.formatFrameTime(-1);
            throw new IllegalStateException("Formatting a negative frame was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("non-negative")) {
                throw new IllegalStateException("Negative frame format error was not reported");
            }
        }
    }

    private static void expectParseFailure(String text, String messagePart) {
        try {
            SimulationSession.parseTimeToFrame(text);
            throw new IllegalStateException("Time input expecting '" + messagePart + "' was accepted");
        } catch (IllegalArgumentException expected) {
            if (expected.getMessage() == null || !expected.getMessage().contains(messagePart)) {
                throw new IllegalStateException("Time parse error '" + messagePart
                        + "' produced: " + expected.getMessage());
            }
        }
    }

    private static void verifyTraceCsvExport() {
        SimulationSession session = new SimulationSession();
        session.newScenario(4, 3);
        session.setTerrain(new UiCell(1, 0), UiTerrain.WALL);
        session.setTerrain(new UiCell(1, 1), UiTerrain.WALL);
        session.setTerrain(new UiCell(1, 2), UiTerrain.WALL);
        session.tickFrame();
        String csv = session.exportTraceCsv();
        boolean diagnosticRow = false;
        for (String row : csv.split("\n")) {
            if (row.startsWith("0,1,") && row.contains("阻挡") && row.contains("blocked unreachable ENDPOINT")) {
                diagnosticRow = true;
            }
        }
        if (!diagnosticRow) {
            throw new IllegalStateException("CSV lost the blocked diagnostic transition row");
        }

        // Multi-unit export is frame-major with one row per unit per frame.
        SimulationSession multi = new SimulationSession();
        multi.newScenario(8, 3);
        multi.addDraft();
        for (int frame = 0; frame < 5; frame++) {
            multi.tickFrame();
        }
        String[] rows = multi.exportTraceCsv().split("\n");
        int frames = multi.generatedFrameCount();
        if (rows.length != frames * 2 + 1) {
            throw new IllegalStateException("Multi-unit CSV row count was " + rows.length
                    + ", expected " + (frames * 2 + 1));
        }
        if (!rows[1].startsWith("0,0,") || !rows[2].startsWith("1,0,")
                || !rows[3].startsWith("0,1,") || !rows[4].startsWith("1,1,")) {
            throw new IllegalStateException("CSV rows are not frame-major across units");
        }
    }

    private static void verifyCheckpointArgumentValidation() {
        try {
            new UiCheckpoint(UiCheckpointType.MOVE, new UiPoint(1.5f, 1.5f), 5f, 0);
            throw new IllegalStateException("A MOVE checkpoint carrying seconds was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("秒数")) {
                throw new IllegalStateException("Misplaced-argument error was not reported");
            }
        }
        try {
            new UiCheckpoint(UiCheckpointType.DISAPPEAR, null, 0f, 3);
            throw new IllegalStateException("A DISAPPEAR checkpoint carrying an area was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("区块")) {
                throw new IllegalStateException("Misplaced-area error was not reported");
            }
        }

        // Updating to a type that ignores the spinners normalizes them away.
        SimulationSession session = new SimulationSession();
        session.newScenario(6, 3);
        session.addCheckpoint(UiCheckpoint.waitForSeconds(1f));
        session.updateCheckpoint(0, UiCheckpointType.ALERT, 5f, 3);
        String exported = session.exportScenario();
        if (!exported.contains("checkpoint ALERT\n")) {
            throw new IllegalStateException("Type update kept irrelevant arguments: " + exported);
        }

        // The enum factory is the single type-to-construction mapping.
        UiCheckpoint created = UiCheckpointType.WAIT_FOR_SECONDS.create(null, 2f, 0);
        if (created.value() != 2f || created.point() != null) {
            throw new IllegalStateException("Enum factory lost the seconds argument");
        }
        UiCheckpoint moved = UiCheckpointType.PATROL_MOVE.create(new UiPoint(2.5f, 2.5f));
        if (!new UiPoint(2.5f, 2.5f).equals(moved.point())) {
            throw new IllegalStateException("Enum factory lost the point argument");
        }

        // Point -> point-less conversion drops the cell; the reverse still fails.
        SimulationSession conversion = new SimulationSession();
        conversion.updateCheckpoint(0, UiCheckpointType.ALERT, 0f, 0);
        String convertedText = conversion.exportScenario();
        if (!convertedText.contains("checkpoint ALERT\n")
                || convertedText.contains("checkpoint MOVE 5 1")) {
            throw new IllegalStateException("Point-to-flag conversion did not drop the cell: "
                    + convertedText);
        }
        try {
            conversion.updateCheckpoint(0, UiCheckpointType.MOVE, 0f, 0);
            throw new IllegalStateException("Flag-to-point conversion without a cell was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("地图坐标")) {
                throw new IllegalStateException("Missing-cell error was not reported in Chinese: "
                        + expected.getMessage());
            }
        }

        // The format accepts any non-negative finite seconds and any non-negative
        // area; the editor spinners' bounds must match so an imported value is
        // never outside the editor's range.
        SimulationSession big = new SimulationSession();
        big.newScenario(6, 3);
        big.addCheckpoint(UiCheckpoint.waitForSeconds(5000f));
        big.addCheckpoint(UiCheckpoint.waitForBossRushArea(500));
        String bigText = big.exportScenario();
        if (!bigText.contains("WAIT_FOR_SECONDS 5000.0")
                || !bigText.contains("WAIT_BOSSRUSH_WAVE 500")) {
            throw new IllegalStateException("Large checkpoint parameters did not serialize: " + bigText);
        }
        SimulationSession reimport = new SimulationSession();
        reimport.importScenario(bigText);
        if (!reimport.exportScenario().equals(bigText)) {
            throw new IllegalStateException("Large-parameter scenario did not round trip");
        }
    }

    private static void verifyUnitSelectionSemantics() {
        SimulationSession session = new SimulationSession();
        session.newScenario(8, 3);
        try {
            session.selectDraft(1);
            throw new IllegalStateException("Selecting a missing unit was accepted");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains("outside the unit list")) {
                throw new IllegalStateException("Out-of-range selection error was not reported");
            }
        }

        session.addDraft();
        session.addDraft();
        session.selectDraft(2);
        session.removeDraft(0);
        if (session.selectedDraftIndex() != 1 || session.unitCount() != 2) {
            throw new IllegalStateException("Removing an earlier unit did not shift the selection");
        }

        // A run event recorded for unit 1 replays against unit 1 after a backward seek.
        SimulationSession replay = new SimulationSession();
        replay.newScenario(8, 3);
        replay.addDraft();
        replay.selectDraft(1);
        replay.applyStun(1f);
        UiFrame first = replay.tickFrame();
        if (!"眩晕".equals(first.units().get(1).unitMode())
                || !"移动".equals(first.units().get(0).unitMode())) {
            throw new IllegalStateException("The recorded stun did not target the selected unit only");
        }
        replay.seekFrame(0);
        UiFrame replayed = replay.seekFrame(1);
        if (!"眩晕".equals(replayed.units().get(1).unitMode())
                || !"移动".equals(replayed.units().get(0).unitMode())) {
            throw new IllegalStateException("Replay did not re-apply the stun to unit 1");
        }
    }

    /**
     * The bind toggle shows the scheduled intent, not just the current frame's
     * state: a bind recorded for the next tick reads as on, a consumed bind
     * reads as the actual state, and other units' events never leak in.
     */
    private static void verifyBindDisplayState() {
        SimulationSession session = new SimulationSession();
        if (session.bindStateForDisplay()) {
            throw new IllegalStateException("A fresh session displayed a bind intent");
        }
        session.setUnitBound(true);
        if (!session.bindStateForDisplay() || session.snapshot().bound()) {
            throw new IllegalStateException("A scheduled bind was not visible before its tick");
        }
        session.tick();
        if (!session.bindStateForDisplay() || !session.snapshot().bound()) {
            throw new IllegalStateException("A consumed bind did not become the actual state");
        }
        session.setUnitBound(false);
        if (session.bindStateForDisplay() || !session.snapshot().bound()) {
            throw new IllegalStateException("A scheduled unbind was not visible before its tick");
        }
        session.seekFrame(0);
        if (!session.bindStateForDisplay() || session.snapshot().bound()) {
            throw new IllegalStateException("Seeking to the bind's frame must restore its intent display");
        }

        SimulationSession multi = new SimulationSession();
        multi.newScenario(8, 3);
        multi.addDraft();
        multi.selectDraft(1);
        multi.setUnitBound(true);
        multi.selectDraft(0);
        if (multi.bindStateForDisplay()) {
            throw new IllegalStateException("Another unit's bind intent leaked into the display");
        }
    }

    private static void verifyCheckpointRowFormat() {
        UiCheckpoint move = UiCheckpoint.move(new UiPoint(3.5f, 4.5f));
        String active = UiFormat.checkpointRow(1, move, true);
        String idle = UiFormat.checkpointRow(1, move, false);
        if (!active.startsWith("▶") || idle.startsWith("▶")
                || !active.contains("移动") || !active.contains("(3.5, 4.5)")
                || !idle.contains("02")) {
            throw new IllegalStateException("Checkpoint row lost its active marker or detail");
        }
        // Decimal coordinates show up to four trimmed digits.
        UiCheckpoint decimal = UiCheckpoint.move(new UiPoint(6f, 1.1222f));
        String decimalRow = UiFormat.checkpointRow(0, decimal, false);
        if (!decimalRow.contains("(6, 1.1222)")) {
            throw new IllegalStateException("Checkpoint row did not trim decimal coordinates");
        }
    }

    private static void verifyShortcutGuard() {
        if (SimulatorWorkbench.globalShortcutAllowed(new javax.swing.JTextField())
                || !SimulatorWorkbench.globalShortcutAllowed(null)
                || !SimulatorWorkbench.globalShortcutAllowed(new javax.swing.JLabel())) {
            throw new IllegalStateException("Global shortcut guard misclassified focus owners");
        }
    }

    private static void verifyRejectionFlash(UiSnapshot snapshot) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
                canvas.setSnapshot(snapshot);
                canvas.setViewportSize(800, 560);
                canvas.setZoom(canvas.fitZoom());
                canvas.setSize(canvas.getPreferredSize());
                canvas.flashRejection(new UiCell(2, 2));
                BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    canvas.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                boolean highlighted = false;
                for (double worldX = 2.05d; worldX < 2.95d && !highlighted; worldX += 0.05d) {
                    // Cell (2,2) spans world y [2,3]; its screen-bottom edge is y=2.
                    Point point = canvas.canvasPointForWorld(worldX, 2.0d);
                    highlighted = isTrajectoryRed(image, point.x, point.y);
                }
                if (!highlighted) {
                    throw new IllegalStateException("Rejection flash drew no highlight on the refused cell");
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while verifying the rejection flash", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Rejection flash verification failed", exception.getCause());
        }
    }

    /**
     * The canvas displays world y upward: a low-y marker must land in the
     * lower half of the screen, a high-y marker in the upper half.
     */
    private static void verifyDisplayYUp() {
        SimulationSession session = new SimulationSession();
        UiSnapshot snapshot = session.snapshot(); // demo: spawn(1,1) endpoint(10,6) on 12x8
        try {
            SwingUtilities.invokeAndWait(() -> {
                SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
                canvas.setSnapshot(snapshot);
                canvas.setViewportSize(800, 560);
                canvas.setZoom(canvas.fitZoom());
                canvas.setSize(canvas.getPreferredSize());
                BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    canvas.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                // Spawn is at world y=1 (low) -> canvas y must be in the lower half.
                Point spawn = canvas.canvasPointForWorld(1.5d, 1.5d);
                // Endpoint is at world y=6 (high) -> canvas y must be in the upper half.
                Point endpoint = canvas.canvasPointForWorld(10.5d, 6.5d);
                int midY = canvas.getHeight() / 2;
                if (spawn.y <= midY || endpoint.y >= midY) {
                    throw new IllegalStateException(
                            "Y-axis flip wrong: spawn screen y=" + spawn.y + " (want > " + midY
                                    + "), endpoint screen y=" + endpoint.y + " (want < " + midY + ")");
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while verifying the y-axis display", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Y-axis display verification failed", exception.getCause());
        }
    }

    /**
     * The painted terrain must line up with the continuous-coordinate markers:
     * the WALL cell's logical center must show wall pixels, the row above it
     * must stay open, and the row below must not carry the wall. Guards the
     * cell-rectangle top edge against an off-by-one after the y-up flip.
     */
    private static void verifyTerrainAlignment(UiSnapshot snapshot) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
                canvas.setSnapshot(snapshot);
                canvas.setViewportSize(800, 560);
                canvas.setZoom(canvas.fitZoom());
                canvas.setSize(canvas.getPreferredSize());
                BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    canvas.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                // Demo terrain: WALL at (7,5), so its center is world (7.5, 5.5).
                if (!isWallLike(image, canvas.canvasPointForWorld(7.5d, 5.5d))) {
                    throw new IllegalStateException("Wall terrain not painted at its logical center");
                }
                if (isWallLike(image, canvas.canvasPointForWorld(7.5d, 4.5d))
                        || isWallLike(image, canvas.canvasPointForWorld(7.5d, 6.5d))) {
                    throw new IllegalStateException("Wall terrain leaked into an adjacent row");
                }
                if (!isOpenLike(image, canvas.canvasPointForWorld(7.5d, 6.5d))) {
                    throw new IllegalStateException("Row above the wall is not open terrain");
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while verifying terrain alignment", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Terrain alignment verification failed", exception.getCause());
        }
    }

    private static boolean isWallLike(BufferedImage image, Point p) {
        if (p.x < 0 || p.y < 0 || p.x >= image.getWidth() || p.y >= image.getHeight()) {
            return false;
        }
        int c = image.getRGB(p.x, p.y);
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r < 150 && g >= r && b >= r;
    }

    private static boolean isOpenLike(BufferedImage image, Point p) {
        if (p.x < 0 || p.y < 0 || p.x >= image.getWidth() || p.y >= image.getHeight()) {
            return false;
        }
        int c = image.getRGB(p.x, p.y);
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return r > 200 && g > 200 && b > 200;
    }

    private static void verifyTrajectoryTooltip(SimulationCanvas canvas) {
        UiSnapshot first = tooltipSnapshot(3, new UiPoint(2f, 2f), false);
        UiSnapshot second = tooltipSnapshot(4, new UiPoint(4f, 2f), false);
        canvas.setTrajectory(List.of(first, second));
        canvas.setShowTrajectory(true);
        canvas.setZoom(40d);
        Point nearestFirst = canvas.canvasPointForWorld(2.25d, 2f);
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0,
                nearestFirst.x, nearestFirst.y, 0, false);
        String tooltip = canvas.getToolTipText(event);
        if (!"\u7b2c 3 \u5e27\uff1a\u4f4d\u7f6e (2.0000, 2.0000)".equals(tooltip)) {
            throw new IllegalStateException("Trajectory hover did not show the nearest real sampled position");
        }
        canvas.dispatchEvent(event);
        canvas.setSize(800, 560);
        BufferedImage hoverImage = new BufferedImage(800, 560, BufferedImage.TYPE_INT_ARGB);
        Graphics2D hoverGraphics = hoverImage.createGraphics();
        try {
            canvas.paint(hoverGraphics);
        } finally {
            hoverGraphics.dispose();
        }
        Point selectedPoint = canvas.canvasPointForWorld(2d, 2d);
        if (!isTrajectoryRed(hoverImage, selectedPoint.x, selectedPoint.y)) {
            throw new IllegalStateException("Trajectory hover did not mark the selected real sample");
        }
        Point tooFar = canvas.canvasPointForWorld(6d, 2f);
        MouseEvent farEvent = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0,
                tooFar.x, tooFar.y, 0, false);
        if (canvas.getToolTipText(farEvent) != null) {
            throw new IllegalStateException("Trajectory hover showed a sample outside its hit radius");
        }
        UiSnapshot portalArrival = tooltipSnapshot(5, new UiPoint(6f, 2f), true);
        canvas.setTrajectory(List.of(first, portalArrival));
        Point nearestPortalArrival = canvas.canvasPointForWorld(5.8d, 2f);
        MouseEvent portalEvent = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0,
                nearestPortalArrival.x, nearestPortalArrival.y, 0, false);
        if (!"\u7b2c 5 \u5e27\uff1a\u4f4d\u7f6e (6.0000, 2.0000)".equals(canvas.getToolTipText(portalEvent))) {
            throw new IllegalStateException("Portal hover did not select the real arrival sample");
        }
        canvas.setShowTrajectory(false);
        if (canvas.getToolTipText(portalEvent) != null) {
            throw new IllegalStateException("Disabled trajectory still showed a hover sample");
        }
        canvas.setShowTrajectory(true);
        canvas.setTrajectory(List.of());
        if (canvas.getToolTipText(portalEvent) != null) {
            throw new IllegalStateException("Empty trajectory still showed a hover sample");
        }
    }

    private static void verifyHoverInvalidation(UiSnapshot snapshot) {
        SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
        canvas.setSnapshot(snapshot);
        UiSnapshot first = tooltipSnapshot(3, new UiPoint(2f, 2f), false);
        UiSnapshot second = tooltipSnapshot(4, new UiPoint(4f, 2f), false);
        canvas.setTrajectory(List.of(first, second));
        canvas.setViewportSize(800, 560);
        canvas.setZoom(1_000d);
        canvas.setSize(canvas.getPreferredSize());
        RepaintManager repaintManager = RepaintManager.currentManager(canvas);
        repaintManager.markCompletelyClean(canvas);
        Point nearFirst = canvas.canvasPointForWorld(2.01d, 2d);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0,
                nearFirst.x, nearFirst.y, 0, false));
        Rectangle dirty = repaintManager.getDirtyRegion(canvas);
        if (dirty.width > 600 || dirty.height > 180) {
            throw new IllegalStateException("Hover movement invalidated the full high-zoom map");
        }
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
        SimulationCanvas canvas = new SimulationCanvas((cell, point) -> { });
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
                UiMovementMode.GROUND, 1f, true, frame, "移动", 0, false, false, false,
                entityPosition, entityPosition, UiPoint.ZERO, UiPoint.ZERO, UiPoint.ZERO,
                new UiCell(0, 1), null, null, false, "APPEAR_AT_POS", 0f, List.of(), trajectoryBreak);
    }

    private static UiSnapshot tooltipSnapshot(int frame, UiPoint entityPosition, boolean trajectoryBreak) {
        return new UiSnapshot(8, 3, java.util.Collections.nCopies(24, UiTerrain.OPEN),
                new UiPoint(0.5f, 1.5f), new UiPoint(7.5f, 1.5f), List.of(),
                UiMovementMode.GROUND, 1f, true, frame, "移动", 0, false, false, false,
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

    /** FlatLaf carries every chrome control; the workbench canvas keeps UiTheme. */
    private static void configureLookAndFeel(UiTheme theme) {
        try {
            if (theme == UiTheme.DARK) {
                com.formdev.flatlaf.FlatDarkLaf.setup();
            } else {
                com.formdev.flatlaf.FlatLightLaf.setup();
            }
        } catch (Throwable missingFlatLaf) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Swing's cross-platform look and feel is a valid fallback.
            }
        }
    }
}
