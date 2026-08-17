import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Swing workbench. SimulationSession remains the only UI-to-core adapter. */
public final class SimulatorWorkbench extends JFrame {
    private static final Color WINDOW_BACKGROUND = new Color(242, 246, 243);
    private static final Color PANEL_BACKGROUND = new Color(250, 252, 250);
    private static final Color BORDER = new Color(196, 207, 200);
    private static final Color VALUE = new Color(31, 48, 41);

    private final SimulationSession session = new SimulationSession();
    private final SimulationCanvas canvas = new SimulationCanvas(this::applyEditorTool);
    private final JScrollPane mapScrollPane = new JScrollPane(canvas,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    private final Timer playbackTimer = new Timer(33, event -> advancePlayback());
    private final ExecutorService seekExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "simulation-seek");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong seekRequest = new AtomicLong();
    private Future<?> pendingSeek;
    private final JToggleButton playButton = new JToggleButton("▶");
    private final JComboBox<String> playbackRate = new JComboBox<>(new String[]{"1×", "3×", "10×"});
    private final JCheckBox showPathToggle = new JCheckBox("路线图", false);
    private final JCheckBox showTrajectoryToggle = new JCheckBox("实际轨迹", true);
    private final JSlider timelineSlider = new JSlider(0, 0, 0);
    private final JTextField timeInput = new JTextField("0", 10);
    private final JLabel frameValue = valueLabel();
    private final JLabel timeValue = valueLabel();
    private final JLabel zoomValue = valueLabel();
    private final JLabel modeValue = valueLabel();
    private final JLabel checkpointValue = valueLabel();
    private final JLabel positionValue = valueLabel();
    private final JLabel velocityValue = valueLabel();
    private final JLabel avoidanceValue = valueLabel();
    private final JLabel targetValue = valueLabel();
    private final JLabel nextNodeValue = valueLabel();
    private final JLabel statusValue = valueLabel();
    private final JLabel footerStatus = new JLabel();
    private final JLabel sliderFrameValue = new JLabel("帧 0");
    private final JLabel sliderTimeValue = new JLabel("0 / 30 s");
    private final JSpinner mapWidthSpinner = new JSpinner(new SpinnerNumberModel(12, 2, 64, 1));
    private final JSpinner mapHeightSpinner = new JSpinner(new SpinnerNumberModel(8, 2, 64, 1));
    private final JComboBox<UiMovementMode> movementModeBox = new JComboBox<>(UiMovementMode.values());
    private final JSpinner speedSpinner = new JSpinner(new SpinnerNumberModel(1.0d, 0.1d, 10.0d, 0.1d));
    private final JCheckBox diagonalToggle = new JCheckBox("允许斜向连线", true);
    private final javax.swing.DefaultListModel<String> checkpointModel = new javax.swing.DefaultListModel<>();
    private final JList<String> checkpointList = new JList<>(checkpointModel);

    private EditorTool selectedTool = EditorTool.OPEN;
    private boolean refreshing;
    private boolean initialMapFitPending = true;
    private int lastViewportWidth;
    private int lastViewportHeight;

    public SimulatorWorkbench() {
        super("寻路模拟器");
        playbackTimer.setCoalesce(true);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(800, 560));
        setPreferredSize(new Dimension(1180, 760));
        setContentPane(createContent());
        canvas.setShowPath(false);
        canvas.setZoomChangeListener(this::refreshZoomLabel);
        canvas.setViewportSize(mapScrollPane.getViewport().getWidth(), mapScrollPane.getViewport().getHeight());
        bindConfigurationControls();
        bindTimelineControls();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                seekRequest.incrementAndGet();
                if (pendingSeek != null) {
                    pendingSeek.cancel(true);
                }
                seekExecutor.shutdownNow();
            }
        });
        refresh(session.snapshot());
        pack();
        setLocationByPlatform(true);
        SwingUtilities.invokeLater(this::updateViewportCamera);
    }

    private JPanel createContent() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(WINDOW_BACKGROUND);
        root.setBorder(new EmptyBorder(10, 12, 10, 12));
        root.add(createToolbar(), BorderLayout.NORTH);
        root.add(createCanvasPanel(), BorderLayout.CENTER);
        root.add(createSidebar(), BorderLayout.EAST);
        root.add(createPlaybackPanel(), BorderLayout.SOUTH);
        return root;
    }

    private JToolBar createToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBackground(PANEL_BACKGROUND);
        toolbar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(5, 7, 5, 7)));
        JLabel title = new JLabel("寻路工作台");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setForeground(VALUE);
        toolbar.add(title);
        toolbar.addSeparator(new Dimension(16, 1));
        JButton step = iconButton("⏭", "推进一帧");
        step.addActionListener(event -> {
            stopPlayback();
            if (!session.canTick()) {
                footerStatus.setText("模拟已到达终态");
                return;
            }
            invalidateSeek();
            refresh(session.tick());
        });
        toolbar.add(step);
        playButton.setToolTipText("运行");
        playButton.setFocusable(false);
        playButton.setPreferredSize(new Dimension(38, 29));
        playButton.addActionListener(event -> togglePlayback());
        toolbar.add(playButton);
        JButton reset = iconButton("↺", "重置运行");
        reset.addActionListener(event -> {
            stopPlayback();
            invalidateSeek();
            refresh(session.resetSimulation());
        });
        toolbar.add(reset);
        toolbar.addSeparator(new Dimension(12, 1));
        toolbar.add(new JLabel("回放"));
        playbackRate.setMaximumSize(new Dimension(78, 29));
        toolbar.add(playbackRate);
        toolbar.addSeparator(new Dimension(10, 1));
        showPathToggle.setOpaque(false);
        showPathToggle.setToolTipText("显示规划路线图");
        showPathToggle.addActionListener(event -> canvas.setShowPath(showPathToggle.isSelected()));
        toolbar.add(showPathToggle);
        showTrajectoryToggle.setOpaque(false);
        showTrajectoryToggle.setToolTipText("显示已生成帧的实际敌人轨迹");
        showTrajectoryToggle.addActionListener(event -> canvas.setShowTrajectory(showTrajectoryToggle.isSelected()));
        toolbar.add(showTrajectoryToggle);
        return toolbar;
    }

    private JPanel createCanvasPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(PANEL_BACKGROUND);
        panel.setBorder(BorderFactory.createLineBorder(BORDER));
        JPanel zoomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        zoomBar.setBackground(PANEL_BACKGROUND);
        zoomBar.add(new JLabel("缩放"));
        zoomValue.setHorizontalAlignment(SwingConstants.LEFT);
        zoomBar.add(zoomValue);
        panel.add(zoomBar, BorderLayout.NORTH);
        mapScrollPane.setBorder(null);
        mapScrollPane.getViewport().setBackground(canvas.getBackground());
        mapScrollPane.getViewport().addChangeListener(event -> {
            updateViewportCamera();
        });
        panel.add(mapScrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createPlaybackPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 2));
        panel.setBackground(WINDOW_BACKGROUND);
        JPanel labels = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        labels.setOpaque(false);
        labels.add(sliderFrameValue);
        labels.add(sliderTimeValue);
        labels.add(new JLabel("精确时间"));
        timeInput.setColumns(11);
        timeInput.setToolTipText("输入帧号、n/30 或可精确换算为帧的秒数");
        labels.add(timeInput);
        JButton seek = new JButton("定位");
        seek.setToolTipText("跳转到精确帧");
        seek.addActionListener(event -> seekFromInput());
        labels.add(seek);
        panel.add(labels, BorderLayout.NORTH);
        timelineSlider.setPaintTicks(false);
        timelineSlider.setFocusable(true);
        timelineSlider.setToolTipText("时间轴单位为帧");
        panel.add(timelineSlider, BorderLayout.CENTER);
        return panel;
    }

    private JScrollPane createSidebar() {
        JPanel content = verticalPanel();
        content.setOpaque(true);
        content.setBackground(WINDOW_BACKGROUND);
        content.add(createMapSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createMovementSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createToolSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createRuntimeSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createCheckpointSection());
        JScrollPane scroll = new JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(260, 0));
        scroll.setMinimumSize(new Dimension(218, 0));
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(WINDOW_BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JPanel createMapSection() {
        JPanel section = section("地图");
        section.setLayout(new GridBagLayout());
        GridBagConstraints c = baseConstraints();
        addFormRow(section, c, 0, "宽", mapWidthSpinner);
        addFormRow(section, c, 1, "高", mapHeightSpinner);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        JButton fresh = new JButton("新建");
        fresh.addActionListener(event -> {
            stopPlayback();
            invalidateSeek();
            session.newScenario(intValue(mapWidthSpinner), intValue(mapHeightSpinner));
            refresh(session.snapshot());
            requestMapFit();
        });
        JButton demo = new JButton("示例");
        demo.addActionListener(event -> {
            stopPlayback();
            invalidateSeek();
            session.loadDemoScenario();
            refresh(session.snapshot());
            requestMapFit();
        });
        actions.add(fresh);
        actions.add(demo);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.insets = new Insets(7, 0, 0, 0);
        section.add(actions, c);
        return section;
    }

    private JPanel createMovementSection() {
        JPanel section = section("移动");
        section.setLayout(new GridBagLayout());
        GridBagConstraints c = baseConstraints();
        addFormRow(section, c, 0, "类型", movementModeBox);
        addFormRow(section, c, 1, "速度", speedSpinner);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        diagonalToggle.setOpaque(false);
        section.add(diagonalToggle, c);
        return section;
    }

    private JPanel createToolSection() {
        JPanel section = section("编辑");
        section.setLayout(new GridLayout(2, 4, 4, 4));
        ButtonGroup group = new ButtonGroup();
        section.add(toolButton(group, EditorTool.OPEN, new SwatchIcon(SimulationCanvas.terrainColor(UiTerrain.OPEN)), "通路"));
        section.add(toolButton(group, EditorTool.BOX, new SwatchIcon(SimulationCanvas.terrainColor(UiTerrain.BOX)), "箱子"));
        section.add(toolButton(group, EditorTool.PIT, new SwatchIcon(SimulationCanvas.terrainColor(UiTerrain.PIT)), "坑"));
        section.add(toolButton(group, EditorTool.WALL, new SwatchIcon(SimulationCanvas.terrainColor(UiTerrain.WALL)), "墙"));
        section.add(toolButton(group, EditorTool.SPAWN, "S", "起点"));
        section.add(toolButton(group, EditorTool.ENDPOINT, "E", "终点"));
        section.add(toolButton(group, EditorTool.CHECKPOINT, "+", "添加移动检查点"));
        section.add(toolButton(group, EditorTool.BROWSE, "浏览", "浏览地图：左键拖动平移"));
        return section;
    }

    private JPanel createRuntimeSection() {
        JPanel section = section("运行状态");
        section.setLayout(new GridBagLayout());
        addStatistic(section, 0, "帧", frameValue);
        addStatistic(section, 1, "时间", timeValue);
        addStatistic(section, 2, "模式", modeValue);
        addStatistic(section, 3, "检查点", checkpointValue);
        addStatistic(section, 4, "位置", positionValue);
        addStatistic(section, 5, "惯性", velocityValue);
        addStatistic(section, 6, "避障", avoidanceValue);
        addStatistic(section, 7, "目标", targetValue);
        addStatistic(section, 8, "下一节点", nextNodeValue);
        addStatistic(section, 9, "事件", statusValue);
        return section;
    }

    private JPanel createCheckpointSection() {
        JPanel section = section("检查点");
        section.setLayout(new BorderLayout(5, 5));
        checkpointList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        checkpointList.setVisibleRowCount(5);
        section.add(new JScrollPane(checkpointList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        JButton remove = iconButton("−", "删除选中的检查点");
        remove.addActionListener(event -> {
            int index = checkpointList.getSelectedIndex();
            if (index >= 0) {
                stopPlayback();
                invalidateSeek();
                session.removeCheckpoint(index);
                refresh(session.snapshot());
            }
        });
        JButton clear = iconButton("×", "清空检查点");
        clear.addActionListener(event -> {
            stopPlayback();
            invalidateSeek();
            session.clearCheckpoints();
            refresh(session.snapshot());
        });
        actions.add(remove);
        actions.add(clear);
        section.add(actions, BorderLayout.SOUTH);
        return section;
    }

    private void bindConfigurationControls() {
        movementModeBox.addActionListener(event -> {
            if (!refreshing) {
                stopPlayback();
                invalidateSeek();
                session.setMovementMode((UiMovementMode) movementModeBox.getSelectedItem());
                refresh(session.snapshot());
            }
        });
        speedSpinner.addChangeListener(event -> {
            if (!refreshing) {
                stopPlayback();
                invalidateSeek();
                session.setAttributeSpeed(((Number) speedSpinner.getValue()).floatValue());
                refresh(session.snapshot());
            }
        });
        diagonalToggle.addActionListener(event -> {
            if (!refreshing) {
                stopPlayback();
                invalidateSeek();
                session.setAllowDiagonalMove(diagonalToggle.isSelected());
                refresh(session.snapshot());
            }
        });
    }

    private void bindTimelineControls() {
        timelineSlider.addChangeListener(event -> {
            if (refreshing) {
                return;
            }
            int target = timelineSlider.getValue();
            showTimelineSelection(target);
            if (!timelineSlider.getValueIsAdjusting()) {
                requestSeek(target);
            }
        });
        timeInput.addActionListener(event -> seekFromInput());
    }

    private void applyEditorTool(UiCell cell) {
        boolean applied = switch (selectedTool) {
            case OPEN -> session.setTerrain(cell, UiTerrain.OPEN);
            case BOX -> session.setTerrain(cell, UiTerrain.BOX);
            case PIT -> session.setTerrain(cell, UiTerrain.PIT);
            case WALL -> session.setTerrain(cell, UiTerrain.WALL);
            case SPAWN -> {
                session.placeSpawn(cell);
                yield true;
            }
            case ENDPOINT -> {
                session.placeEndpoint(cell);
                yield true;
            }
            case CHECKPOINT -> {
                session.addCheckpoint(cell);
                yield true;
            }
            case BROWSE -> true;
        };
        if (!applied) {
            footerStatus.setText("该格已被起点、终点或检查点占用，不能放置地形");
            return;
        }
        stopPlayback();
        invalidateSeek();
        refresh(session.snapshot());
    }

    private void togglePlayback() {
        if (!playButton.isSelected()) {
            stopPlayback();
            return;
        }
        playButton.setText("Ⅱ");
        playButton.setToolTipText("暂停");
        playbackTimer.start();
    }

    private void advancePlayback() {
        UiSnapshot current = null;
        try {
            for (int index = 0; index < framesPerTimerTick(); index++) {
                current = session.tick();
                if (current.terminal()) {
                    break;
                }
            }
        } catch (SimulationSession.TerminalStateException terminal) {
            stopPlayback();
        }
        refresh(current == null ? session.snapshot() : current);
        if (current != null && current.terminal()) {
            stopPlayback();
        }
    }

    private void stopPlayback() {
        playbackTimer.stop();
        playButton.setSelected(false);
        playButton.setText("▶");
        playButton.setToolTipText("运行");
    }

    private int framesPerTimerTick() {
        return switch (playbackRate.getSelectedIndex()) {
            case 1 -> 3;
            case 2 -> 10;
            default -> 1;
        };
    }

    private void seekFromInput() {
        final long frame;
        try {
            frame = SimulationSession.parseTimeToFrame(timeInput.getText());
        } catch (IllegalArgumentException error) {
            footerStatus.setText(error.getMessage());
            timeInput.selectAll();
            return;
        }
        stopPlayback();
        requestSeek(frame);
    }

    private void requestSeek(long frame) {
        if (frame < 0L || frame > Integer.MAX_VALUE - 1L) {
            footerStatus.setText("帧号超出范围");
            return;
        }
        long request = seekRequest.incrementAndGet();
        if (pendingSeek != null) {
            pendingSeek.cancel(true);
        }
        long revision = session.scenarioRevision();
        pendingSeek = seekExecutor.submit(() -> {
            try {
                UiSnapshot result = session.seekFrame(frame);
                SwingUtilities.invokeLater(() -> {
                    if (request != seekRequest.get() || revision != session.scenarioRevision()) {
                        return;
                    }
                    refresh(result);
                });
            } catch (RuntimeException error) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    if (request == seekRequest.get()) {
                        footerStatus.setText(error.getMessage());
                    }
                });
            }
        });
    }

    private void invalidateSeek() {
        seekRequest.incrementAndGet();
        if (pendingSeek != null) {
            pendingSeek.cancel(true);
            pendingSeek = null;
        }
    }

    private void showTimelineSelection(int frame) {
        try {
            UiSnapshot generated = session.generatedStateAtFrame(frame);
            if (generated != null) {
                refresh(generated);
            } else {
                sliderFrameValue.setText("帧 " + frame);
                sliderTimeValue.setText(SimulationSession.formatFrameTime(frame));
            }
        } catch (RuntimeException ignored) {
            // The background seek reports terminal or validation errors.
        }
    }

    private void refresh(UiSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        refreshing = true;
        try {
            mapWidthSpinner.setValue(snapshot.width());
            mapHeightSpinner.setValue(snapshot.height());
            movementModeBox.setSelectedItem(snapshot.movementMode());
            speedSpinner.setValue((double) snapshot.attributeSpeed());
            diagonalToggle.setSelected(snapshot.allowDiagonalMove());
            canvas.setSnapshot(snapshot);
            canvas.setTrajectory(showTrajectoryToggle.isSelected()
                    ? trajectoryThroughFrame(snapshot.frame()) : List.of());
            timelineSlider.setMaximum(Math.max(0, session.generatedLastFrame()));
            timelineSlider.setValue(Math.min(snapshot.frame(), timelineSlider.getMaximum()));
            frameValue.setText(Integer.toString(snapshot.frame()));
            timeValue.setText(SimulationSession.formatFrameTime(snapshot.frame()));
            sliderFrameValue.setText("帧 " + snapshot.frame());
            sliderTimeValue.setText(SimulationSession.formatFrameTime(snapshot.frame()));
            // Keep whatever the user is typing in the seek field; a later blur
            // or an actual seek refreshes it with the confirmed frame.
            if (!timeInput.hasFocus()) {
                timeInput.setText(Long.toString(snapshot.frame()));
            }
            modeValue.setText(snapshot.unitMode());
            checkpointValue.setText(checkpointLabel(snapshot));
            positionValue.setText(formatPoint(snapshot.entityPosition()));
            velocityValue.setText(formatPoint(snapshot.inertiaVelocity()));
            avoidanceValue.setText(formatPoint(snapshot.avoidance())
                    + (snapshot.avoidanceRecomputed() ? "  刷新" : ""));
            targetValue.setText(formatPoint(snapshot.target()));
            nextNodeValue.setText(formatCell(snapshot.nextNode()));
            statusValue.setText(snapshot.transition().isBlank() ? "-" : snapshot.transition());
            footerStatus.setText(statusLabel(snapshot));
            refreshCheckpointList(snapshot);
            refreshZoomLabel();
        } finally {
            refreshing = false;
        }
    }

    private void applyRequestedViewPosition() {
        canvas.applyRequestedViewPosition();
    }

    private void updateViewportCamera() {
        int width = mapScrollPane.getViewport().getWidth();
        int height = mapScrollPane.getViewport().getHeight();
        if (width <= 1 || height <= 1) {
            return;
        }
        if (initialMapFitPending) {
            initialMapFitPending = false;
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
        applyRequestedViewPosition();
        refreshZoomLabel();
    }

    private void requestMapFit() {
        initialMapFitPending = true;
        lastViewportWidth = -1;
        lastViewportHeight = -1;
        SwingUtilities.invokeLater(this::updateViewportCamera);
    }

    private void refreshZoomLabel() {
        zoomValue.setText(String.format(Locale.ROOT, "%.0f%%", canvas.zoomPercent()));
    }

    private List<UiSnapshot> trajectoryThroughFrame(int frame) {
        List<UiSnapshot> states = session.generatedStates();
        int inclusiveCount = Math.min(states.size(), Math.max(0, frame) + 1);
        return states.subList(0, inclusiveCount);
    }

    private void refreshCheckpointList(UiSnapshot snapshot) {
        checkpointModel.clear();
        for (int index = 0; index < snapshot.checkpoints().size(); index++) {
            UiPoint point = snapshot.checkpoints().get(index);
            checkpointModel.addElement(String.format(Locale.ROOT, "%02d   (%.1f, %.1f)",
                    index + 1, point.x(), point.y()));
        }
        if (snapshot.activeCheckpoint() >= 0 && snapshot.activeCheckpoint() < checkpointModel.size()) {
            checkpointList.setSelectedIndex(snapshot.activeCheckpoint());
        }
    }

    private static String checkpointLabel(UiSnapshot snapshot) {
        if (snapshot.completed()) {
            return "已完成";
        }
        int count = snapshot.checkpoints().size();
        return snapshot.activeCheckpoint() < count ? (snapshot.activeCheckpoint() + 1) + " / " + count : "终点";
    }

    private static String statusLabel(UiSnapshot snapshot) {
        if (!snapshot.transition().isBlank()) {
            return snapshot.transition();
        }
        if (snapshot.completed()) {
            return "已到达终点";
        }
        return snapshot.avoidanceRecomputed() ? "避障已刷新" : "就绪";
    }

    private static String formatPoint(UiPoint point) {
        return point == null ? "-" : String.format(Locale.ROOT, "%.4f, %.4f", point.x(), point.y());
    }

    private static String formatCell(UiCell cell) {
        return cell == null ? "-" : cell.x() + ", " + cell.y();
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    private static JPanel section(String title) {
        JPanel panel = new JPanel();
        panel.setBackground(PANEL_BACKGROUND);
        TitledBorder border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(BORDER), title);
        border.setTitleColor(VALUE);
        panel.setBorder(BorderFactory.createCompoundBorder(border, new EmptyBorder(5, 6, 6, 6)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static GridBagConstraints baseConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1d;
        c.insets = new Insets(2, 0, 2, 0);
        return c;
    }

    private static void addFormRow(JPanel panel, GridBagConstraints c, int row, String label, Component component) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0d;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1d;
        panel.add(component, c);
    }

    private static void addStatistic(JPanel panel, int row, String label, JLabel value) {
        GridBagConstraints c = baseConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0d;
        c.insets = new Insets(2, 0, 2, 7);
        JLabel name = new JLabel(label);
        name.setForeground(new Color(94, 111, 101));
        panel.add(name, c);
        c.gridx = 1;
        c.weightx = 1d;
        c.insets = new Insets(2, 0, 2, 0);
        panel.add(value, c);
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel("-");
        label.setForeground(VALUE);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    private static JButton iconButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(2, 7, 2, 7));
        return button;
    }

    private JToggleButton toolButton(ButtonGroup group, EditorTool tool, Icon icon, String tooltip) {
        JToggleButton button = new JToggleButton(icon);
        configureToolButton(group, tool, button, tooltip);
        return button;
    }

    private JToggleButton toolButton(ButtonGroup group, EditorTool tool, String text, String tooltip) {
        JToggleButton button = new JToggleButton(text);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        configureToolButton(group, tool, button, tooltip);
        return button;
    }

    private void configureToolButton(ButtonGroup group, EditorTool tool, JToggleButton button, String tooltip) {
        group.add(button);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setPreferredSize(new Dimension(34, 32));
        button.setMargin(new Insets(2, 2, 2, 2));
        button.addActionListener(event -> {
            selectedTool = tool;
            canvas.setBrowseMode(tool == EditorTool.BROWSE);
        });
        if (tool == selectedTool) {
            button.setSelected(true);
        }
    }

    private static int intValue(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    private enum EditorTool { OPEN, BOX, PIT, WALL, SPAWN, ENDPOINT, CHECKPOINT, BROWSE }

    private static final class SwatchIcon implements Icon {
        private final Color color;

        private SwatchIcon(Color color) {
            this.color = color;
        }

        @Override
        public int getIconWidth() { return 19; }

        @Override
        public int getIconHeight() { return 19; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setColor(color);
                canvas.fillRect(x, y, getIconWidth(), getIconHeight());
                canvas.setColor(new Color(54, 65, 60));
                canvas.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
            } finally {
                canvas.dispose();
            }
        }
    }
}
