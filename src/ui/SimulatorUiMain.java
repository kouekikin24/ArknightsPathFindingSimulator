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
        verifyTerminalFlagAndTerrainRejection();
        verifyScenarioRoundTrip();
        verifyCheckpointEditing();
        verifyCombatStateInjection();
        verifyInjectionBelowFrontier();
        verifyMultiUnit();
        System.out.println("UI session verification passed.");
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
        SimulationCanvas canvas = new SimulationCanvas(cell -> editCount[0]++);
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
    }

    private static void verifyNonPrimaryButtonsDoNotEdit(UiSnapshot snapshot) {
        int[] editCount = {0};
        SimulationCanvas canvas = new SimulationCanvas(cell -> editCount[0]++);
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
        patrolSession.addCheckpoint(UiCheckpoint.move(new UiCell(2, 1)));
        patrolSession.addCheckpoint(UiCheckpoint.patrolMove(new UiCell(5, 1)));
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
        portalSession.addCheckpoint(UiCheckpoint.appearAt(new UiCell(6, 1)));
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

    private static void verifyMultiUnit() {
        SimulationSession session = new SimulationSession();
        session.newScenario(8, 3);
        session.addDraft();
        if (session.unitCount() != 2 || session.selectedDraftIndex() != 1) {
            throw new IllegalStateException("Adding a draft did not create and select a second unit");
        }
        session.selectDraft(1);
        session.placeEndpoint(new UiCell(6, 2));
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

        SimulationCanvas canvas = new SimulationCanvas(cell -> {
        });
        canvas.setSnapshot(frame.units().get(0));
        canvas.setUnits(frame.units());
        verifyCanvasPaint(canvas, 800, 560);
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
        SimulationCanvas canvas = new SimulationCanvas(cell -> { });
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

    private static void configureLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's cross-platform look and feel is a valid fallback.
        }
    }
}
