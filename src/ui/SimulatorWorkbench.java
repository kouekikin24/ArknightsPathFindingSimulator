import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
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
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Swing workbench. SimulationSession remains the only UI-to-core adapter. */
public final class SimulatorWorkbench extends JFrame {
    private String themePreference = themePreference();
    private UiTheme theme = resolvedTheme();
    private Color WINDOW_BACKGROUND = theme.windowBackground();
    private Color PANEL_BACKGROUND = theme.panelBackground();
    private Color BORDER = theme.border();
    private Color VALUE = theme.valueText();
    private Color LABEL_TEXT = theme.labelText();

    private final SimulationSession session = new SimulationSession();
    private final SimulationCanvas canvas = ComponentIds.tag(
            new SimulationCanvas(this::applyEditorTool), "M0", "地图画布");
    private final JScrollPane mapScrollPane = new JScrollPane(canvas,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    private final ExecutorService seekExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "simulation-seek");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong seekRequest = new AtomicLong();
    private Future<?> pendingSeek;
    private final JToggleButton playButton = ComponentIds.tag(new JToggleButton("▶"), "P2", "播放/暂停");
    private final JButton undoButton = ComponentIds.tag(
            iconButton("↶", "撤销上一步场景编辑（Ctrl+Z）"), "P4", "撤销");
    private final JButton redoButton = ComponentIds.tag(iconButton("↷", "重做（Ctrl+Y）"), "P5", "重做");
    private final JComboBox<String> playbackRate = ComponentIds.tag(
            new JComboBox<>(new String[]{"1×", "3×", "10×"}), "P6", "回放速度");
    private final JCheckBox showPathToggle = ComponentIds.tag(
            new JCheckBox("路线图", false), "V1", "路线图开关");
    private final JCheckBox showTrajectoryToggle = ComponentIds.tag(
            new JCheckBox("实际轨迹", true), "V2", "实际轨迹开关");
    private final JCheckBox coordinateToggle = ComponentIds.tag(
            new JCheckBox("坐标", false), "V3", "格子坐标开关");
    private final JSlider timelineSlider = ComponentIds.tag(new JSlider(0, 0, 0), "P7", "时间轴");
    private final JTextField timeInput = ComponentIds.tag(new JTextField("0", 10), "P8", "精确时间输入");
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
    /** Persistent user-action feedback; frame refreshes never overwrite this channel. */
    private final JLabel operationStatus = new JLabel();
    private final JLabel sliderFrameValue = new JLabel("帧 0");
    private final JLabel sliderTimeValue = new JLabel("0 / 30 s");
    // Bounds match the scenario format: dimensions span the codec's full
    // range, speed any finite value of at least 0.1, so an imported scenario
    // is never outside the editor's range (which would dead the step buttons
    // and clamp on edit).
    private final JSpinner mapWidthSpinner = ComponentIds.tag(integerSpinner(new SpinnerNumberModel(12,
            ScenarioCodec.MINIMUM_DIMENSION, ScenarioCodec.MAXIMUM_DIMENSION, 1)), "D1", "地图宽（格）");
    private final JSpinner mapHeightSpinner = ComponentIds.tag(integerSpinner(new SpinnerNumberModel(8,
            ScenarioCodec.MINIMUM_DIMENSION, ScenarioCodec.MAXIMUM_DIMENSION, 1)), "D2", "地图高（格）");
    private final JComboBox<UiMovementMode> movementModeBox = ComponentIds.tag(
            new JComboBox<>(UiMovementMode.values()), "R1", "移动类型");
    private final JSpinner speedSpinner = ComponentIds.tag(cappedEditor(
            decimalSpinner(new SpinnerNumberModel(1.0d, 0.1d, (double) Float.MAX_VALUE, 0.1d)), 5),
            "R2", "移动速度");
    private final JCheckBox diagonalToggle = ComponentIds.tag(
            new JCheckBox("允许斜向连线", true), "R3", "允许斜向连线");
    private final javax.swing.DefaultListModel<String> unitModel = new javax.swing.DefaultListModel<>();
    private final JList<String> unitList = ComponentIds.tag(new JList<>(unitModel), "U1", "单位列表");
    private final JComboBox<UiCheckpointType> checkpointTypeBox = ComponentIds.tag(
            new JComboBox<>(UiCheckpointType.values()), "C3", "检查点类型");
    // Bounds match the scenario format: seconds is any non-negative finite
    // float, area any non-negative int, so an imported value is never outside
    // the editor's range (which would dead the step buttons and clamp on edit).
    // The editor width is capped separately so a huge maximum never widens it.
    private final JSpinner checkpointSecondsSpinner = ComponentIds.tag(cappedEditor(
            decimalSpinner(new SpinnerNumberModel(1.0d, 0.0d, (double) Float.MAX_VALUE, 0.1d)), 6),
            "C4", "检查点参数·秒数");
    private final JSpinner checkpointAreaSpinner = ComponentIds.tag(cappedEditor(
            integerSpinner(new SpinnerNumberModel(1, 0, Integer.MAX_VALUE, 1)), 5),
            "C5", "检查点参数·区块");
    private final JSpinner checkpointXSpinner = ComponentIds.tag(coordinateSpinner(6), "C1", "检查点坐标 X");
    private final JSpinner checkpointYSpinner = ComponentIds.tag(coordinateSpinner(6), "C2", "检查点坐标 Y");
    private final JSpinner spawnXSpinner = ComponentIds.tag(coordinateSpinner(4), "S1", "起点 X");
    private final JSpinner spawnYSpinner = ComponentIds.tag(coordinateSpinner(4), "S2", "起点 Y");
    private final JSpinner endpointXSpinner = ComponentIds.tag(coordinateSpinner(4), "E1", "终点 X");
    private final JSpinner endpointYSpinner = ComponentIds.tag(coordinateSpinner(4), "E2", "终点 Y");
    private final java.awt.CardLayout routePointEditorCards = new java.awt.CardLayout();
    private final JPanel routePointEditorSlot = new JPanel(routePointEditorCards);
    private JLabel checkpointCoordLabel;
    private JPanel checkpointCoordPanel;
    private JScrollPane checkpointListScroll;
    private JScrollPane sidebarScrollPane;
    private javax.swing.Timer checkpointFlashTimer;
    private final JButton addCheckpointButton = ComponentIds.tag(new JButton("添加"), "C7", "添加检查点");
    private final JSpinner stunSecondsSpinner = ComponentIds.tag(
            narrowSpinner(decimalSpinner(new SpinnerNumberModel(1.0d, 0.0d, 60.0d, 0.1d))),
            "B1", "眩晕秒数");
    private final JSpinner pushXSpinner = ComponentIds.tag(
            narrowSpinner(decimalSpinner(new SpinnerNumberModel(0.0d, -10.0d, 10.0d, 0.5d))),
            "B3", "击退 X 速度");
    private final JSpinner pushYSpinner = ComponentIds.tag(
            narrowSpinner(decimalSpinner(new SpinnerNumberModel(0.0d, -10.0d, 10.0d, 0.5d))),
            "B4", "击退 Y 速度");
    private final JSpinner pushSecondsSpinner = ComponentIds.tag(
            narrowSpinner(decimalSpinner(new SpinnerNumberModel(0.5d, 0.0d, 10.0d, 0.1d))),
            "B5", "击退持续秒数");
    private final JToggleButton bindToggle = ComponentIds.tag(new JToggleButton("束缚"), "B7", "束缚开关");
    private final javax.swing.DefaultListModel<String> checkpointModel = new javax.swing.DefaultListModel<>();
    private final JList<String> checkpointList = ComponentIds.tag(
            new JList<>(checkpointModel), "C12", "检查点列表");
    private final ViewportCamera camera = new ViewportCamera(mapScrollPane, canvas, zoomValue);
    private final PlaybackController playback = new PlaybackController(
            session, playButton, playbackRate, operationStatus, this::refresh);
    private final ScenarioFiles scenarioFiles = new ScenarioFiles(session, this, operationStatus, () -> {
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        camera.requestFit();
    });
    private final JButton themeToggleButton = ComponentIds.tag(
            iconButton(themeIcon(themePreference), themeTooltip(themePreference)), "V4", "主题切换");

    private EditorTool selectedTool = EditorTool.OPEN;
    private boolean refreshing;

    public SimulatorWorkbench() {
        super("寻路模拟器");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(800, 560));
        setPreferredSize(new Dimension(1180, 760));
        setContentPane(createContent());
        canvas.setShowPath(false);
        canvas.setTheme(theme);
        mapScrollPane.getViewport().setBackground(theme.canvasBackground());
        // The panels were built with the light palette; recolor them exactly
        // once when the resolved theme is dark. Controls styled by the L&F
        // (FlatLaf already matches the theme) are untouched by this pass.
        if (theme == UiTheme.DARK) {
            recolor(getContentPane(), UiTheme.LIGHT);
        }
        canvas.setZoomChangeListener(camera::refreshZoomLabel);
        canvas.setViewportSize(mapScrollPane.getViewport().getWidth(), mapScrollPane.getViewport().getHeight());
        // Space belongs to play/pause: no non-input control may hold keyboard focus.
        playbackRate.setFocusable(false);
        showPathToggle.setFocusable(false);
        showTrajectoryToggle.setFocusable(false);
        coordinateToggle.setFocusable(false);
        movementModeBox.setFocusable(false);
        checkpointTypeBox.setFocusable(false);
        diagonalToggle.setFocusable(false);
        bindToggle.setFocusable(false);
        addCheckpointButton.setFocusable(false);
        unitList.setFocusable(false);
        checkpointList.setFocusable(false);
        bindConfigurationControls();
        bindTimelineControls();
        bindKeyboardShortcuts();
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
        refresh(session.snapshotFrame());
        pack();
        setLocationByPlatform(true);
        // Hold Ctrl and hover any control to see its component-map ID.
        ComponentInspector.install(this);
        // Every non-input control is unfocusable, so without this no component
        // owns the window focus and WHEN_IN_FOCUSED_WINDOW keys never fire.
        // The slider is the safe focus owner: it only claims the arrow keys.
        SwingUtilities.invokeLater(timelineSlider::requestFocusInWindow);
        SwingUtilities.invokeLater(camera::update);
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
        JButton step = ComponentIds.tag(iconButton("⏭", "推进一帧"), "P1", "推进一帧");
        step.addActionListener(event -> stepOneFrame());
        toolbar.add(step);
        playButton.setToolTipText("运行");
        playButton.setFocusable(false);
        playButton.setPreferredSize(new Dimension(38, 29));
        playButton.addActionListener(event -> playback.toggle());
        toolbar.add(playButton);
        JButton reset = ComponentIds.tag(iconButton("↺", "重置运行"), "P3", "重置运行");
        reset.addActionListener(event -> resetRun());
        toolbar.add(reset);
        undoButton.addActionListener(event -> undoEdit());
        toolbar.add(undoButton);
        redoButton.addActionListener(event -> redoEdit());
        toolbar.add(redoButton);
        toolbar.addSeparator(new Dimension(12, 1));
        JLabel replayLabel = new JLabel("回放");
        replayLabel.setForeground(LABEL_TEXT);
        toolbar.add(replayLabel);
        playbackRate.setMaximumSize(new Dimension(78, 29));
        toolbar.add(playbackRate);
        toolbar.addSeparator(new Dimension(10, 1));
        showPathToggle.setOpaque(false);
        showPathToggle.setForeground(VALUE);
        showPathToggle.setToolTipText("显示规划路线图");
        showPathToggle.addActionListener(event -> canvas.setShowPath(showPathToggle.isSelected()));
        toolbar.add(showPathToggle);
        showTrajectoryToggle.setOpaque(false);
        showTrajectoryToggle.setForeground(VALUE);
        showTrajectoryToggle.setToolTipText("显示已生成帧的实际敌人轨迹");
        showTrajectoryToggle.addActionListener(event -> canvas.setShowTrajectory(showTrajectoryToggle.isSelected()));
        toolbar.add(showTrajectoryToggle);
        coordinateToggle.setOpaque(false);
        coordinateToggle.setForeground(VALUE);
        coordinateToggle.setToolTipText("在每个格子右下角标注格子坐标");
        coordinateToggle.addActionListener(event -> canvas.setShowCoordinates(coordinateToggle.isSelected()));
        toolbar.add(coordinateToggle);
        toolbar.addSeparator(new Dimension(10, 1));
        JButton exportScenarioButton = ComponentIds.tag(
                iconButton("导出场景", "把当前地图与路线保存为可导入的文本文件"), "F1", "导出场景");
        exportScenarioButton.addActionListener(event -> scenarioFiles.exportScenario());
        toolbar.add(exportScenarioButton);
        JButton importScenarioButton = ComponentIds.tag(
                iconButton("导入场景", "从文本文件载入地图与路线"), "F2", "导入场景");
        importScenarioButton.addActionListener(event -> scenarioFiles.importScenario());
        toolbar.add(importScenarioButton);
        JButton exportTraceButton = ComponentIds.tag(
                iconButton("导出轨迹", "把已生成帧导出为逐帧 CSV"), "F3", "导出轨迹");
        exportTraceButton.addActionListener(event -> scenarioFiles.exportTrace());
        toolbar.add(exportTraceButton);
        themeToggleButton.addActionListener(event -> toggleTheme());
        toolbar.add(themeToggleButton);
        return toolbar;
    }

    private JPanel createCanvasPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(PANEL_BACKGROUND);
        panel.setBorder(BorderFactory.createLineBorder(BORDER));
        JPanel zoomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        zoomBar.setBackground(PANEL_BACKGROUND);
        JLabel zoomLabel = new JLabel("缩放");
        zoomLabel.setForeground(LABEL_TEXT);
        zoomBar.add(zoomLabel);
        zoomValue.setHorizontalAlignment(SwingConstants.LEFT);
        zoomBar.add(zoomValue);
        panel.add(zoomBar, BorderLayout.NORTH);
        mapScrollPane.setBorder(null);
        mapScrollPane.getViewport().setBackground(canvas.getBackground());
        mapScrollPane.getViewport().addChangeListener(event -> camera.update());
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
        JLabel exactLabel = new JLabel("精确时间");
        exactLabel.setForeground(LABEL_TEXT);
        labels.add(exactLabel);
        timeInput.setColumns(11);
        timeInput.setToolTipText("输入帧号、n/30 或可精确换算为帧的秒数");
        labels.add(timeInput);
        JButton seek = ComponentIds.tag(new JButton("定位"), "P9", "定位到精确帧");
        seek.setFocusable(false);
        seek.setToolTipText("跳转到精确帧");
        seek.addActionListener(event -> seekFromInput());
        labels.add(seek);
        panel.add(labels, BorderLayout.NORTH);
        timelineSlider.setPaintTicks(false);
        timelineSlider.setFocusable(true);
        timelineSlider.setToolTipText("拖动跳转；←/→ 逐帧、PgUp/PgDn 十帧、Home/End 首末帧");
        panel.add(timelineSlider, BorderLayout.CENTER);
        JPanel statusRow = new JPanel(new BorderLayout(8, 0));
        statusRow.setOpaque(false);
        operationStatus.setForeground(VALUE);
        operationStatus.setBorder(new EmptyBorder(2, 6, 2, 6));
        footerStatus.setForeground(LABEL_TEXT);
        footerStatus.setBorder(new EmptyBorder(2, 6, 2, 6));
        statusRow.add(operationStatus, BorderLayout.CENTER);
        statusRow.add(footerStatus, BorderLayout.EAST);
        panel.add(statusRow, BorderLayout.SOUTH);
        return panel;
    }

    private JScrollPane createSidebar() {
        JPanel content = new SidebarContentPanel();
        content.setOpaque(true);
        content.setBackground(WINDOW_BACKGROUND);
        content.add(createMapSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createUnitSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createMovementSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createToolSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createCheckpointSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createCombatSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createRuntimeSection());
        JScrollPane scroll = new JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sidebarScrollPane = scroll;
        scroll.setPreferredSize(new Dimension(280, 0));
        scroll.setMinimumSize(new Dimension(240, 0));
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(WINDOW_BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /**
     * Runs one scenario edit with the shared bookkeeping epilogue: playback
     * stops, any in-flight seek is invalidated, and the UI refreshes from the
     * session's authoritative snapshot.
     */
    private void runEdit(Runnable edit) {
        edit.run();
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
    }

    private JPanel createMapSection() {
        JPanel section = section("地图");
        section.setLayout(new GridBagLayout());
        GridBagConstraints c = baseConstraints();
        addFormRow(section, c, 0, "宽", mapWidthSpinner);
        addFormRow(section, c, 1, "高", mapHeightSpinner);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        JButton fresh = ComponentIds.tag(new JButton("新建"), "D3", "新建地图");
        fresh.setFocusable(false);
        fresh.addActionListener(event -> {
            commitSpinnerEdits(mapWidthSpinner, mapHeightSpinner);
            runEdit(() -> session.newScenario(intValue(mapWidthSpinner), intValue(mapHeightSpinner)));
            camera.requestFit();
        });
        JButton demo = ComponentIds.tag(new JButton("示例"), "D4", "载入示例地图");
        demo.setFocusable(false);
        demo.addActionListener(event -> {
            runEdit(session::loadDemoScenario);
            camera.requestFit();
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

    private JPanel createUnitSection() {
        JPanel section = section("单位");
        section.setLayout(new BorderLayout(5, 5));
        unitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        unitList.setVisibleRowCount(2);
        section.add(new JScrollPane(unitList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        JButton add = ComponentIds.tag(new JButton("添加"), "U2", "添加单位");
        add.setFocusable(false);
        add.setToolTipText("复制当前路线为新单位");
        add.addActionListener(event -> runEdit(session::addDraft));
        actions.add(add);
        JButton remove = ComponentIds.tag(new JButton("删除"), "U3", "删除单位");
        remove.setFocusable(false);
        remove.setToolTipText("删除选中的单位（至少保留一个）");
        remove.addActionListener(event -> {
            int index = unitList.getSelectedIndex();
            if (index < 0) {
                return;
            }
            try {
                runEdit(() -> session.removeDraft(index));
            } catch (IllegalArgumentException error) {
                operationStatus.setText("无法删除：" + error.getMessage());
            }
        });
        actions.add(remove);
        section.add(actions, BorderLayout.SOUTH);
        unitList.addListSelectionListener(event -> {
            if (refreshing || event.getValueIsAdjusting()) {
                return;
            }
            int index = unitList.getSelectedIndex();
            if (index >= 0 && index != session.selectedDraftIndex()) {
                session.selectDraft(index);
                refresh(session.snapshotFrame());
            }
        });
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
        diagonalToggle.setForeground(VALUE);
        section.add(diagonalToggle, c);
        return section;
    }

    private JPanel createToolSection() {
        JPanel section = section("编辑");
        section.setLayout(new GridBagLayout());
        section.setOpaque(false);
        JPanel tools = new JPanel(new GridLayout(2, 4, 4, 4));
        tools.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        tools.add(ComponentIds.tag(toolButton(group, EditorTool.OPEN,
                new SwatchIcon(canvas, UiTerrain.OPEN), "通路"), "T1", "通路工具"));
        tools.add(ComponentIds.tag(toolButton(group, EditorTool.BOX,
                new SwatchIcon(canvas, UiTerrain.BOX), "箱子"), "T2", "箱子工具"));
        tools.add(ComponentIds.tag(toolButton(group, EditorTool.PIT,
                new SwatchIcon(canvas, UiTerrain.PIT), "坑"), "T3", "坑工具"));
        tools.add(ComponentIds.tag(toolButton(group, EditorTool.WALL,
                new SwatchIcon(canvas, UiTerrain.WALL), "墙"), "T4", "墙工具"));
        tools.add(ComponentIds.tag(toolButton(group, EditorTool.SPAWN, "S", "起点"), "T5", "起点工具"));
        tools.add(ComponentIds.tag(toolButton(group, EditorTool.ENDPOINT, "E", "终点"), "T6", "终点工具"));
        tools.add(ComponentIds.tag(toolButton(group, EditorTool.CHECKPOINT, "+",
                        "添加移动检查点（按住 Shift 点击吸附格心）"), "T7", "检查点工具"));
        tools.add(ComponentIds.tag(toolButton(group, EditorTool.BROWSE, "浏览",
                        "浏览地图：左键拖动平移（任意工具下右键拖动也可平移）"),
                "T8", "浏览（平移）工具"));
        GridBagConstraints c = baseConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1d;
        c.fill = GridBagConstraints.HORIZONTAL;
        section.add(tools, c);
        // The numeric editors live in one fixed CardLayout slot: the S/E
        // tools swap their card in, every other tool shows an empty card, so
        // the sidebar never reshapes around them. X and Y stack as two
        // full-width rows; side by side they would not fit the sidebar width.
        routePointEditorSlot.setOpaque(false);
        JPanel emptyCard = new JPanel();
        emptyCard.setOpaque(false);
        routePointEditorSlot.add(emptyCard, "none");
        routePointEditorSlot.add(coordinateEditorCard(spawnXSpinner, spawnYSpinner), "spawn");
        routePointEditorSlot.add(coordinateEditorCard(endpointXSpinner, endpointYSpinner), "endpoint");
        addFormRow(section, c, 1, "起终点", routePointEditorSlot);
        addRoutePointCommit(spawnXSpinner, spawnYSpinner, true);
        addRoutePointCommit(endpointXSpinner, endpointYSpinner, false);
        updateRoutePointEditorVisibility();
        return section;
    }

    /** Shows exactly the numeric row of the active route-point tool, if any. */
    private void updateRoutePointEditorVisibility() {
        String card = switch (selectedTool) {
            case SPAWN -> "spawn";
            case ENDPOINT -> "endpoint";
            default -> "none";
        };
        routePointEditorCards.show(routePointEditorSlot, card);
    }

    /** One route-point editor card: X and Y as full-width stacked form rows. */
    private JPanel coordinateEditorCard(JSpinner xSpinner, JSpinner ySpinner) {
        JPanel card = new JPanel(new GridBagLayout());
        card.setOpaque(false);
        GridBagConstraints c = baseConstraints();
        c.gridx = 0;
        c.weightx = 0d;
        c.gridy = 0;
        card.add(plainLabel("X"), c);
        c.gridx = 1;
        c.weightx = 1d;
        card.add(xSpinner, c);
        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0d;
        card.add(plainLabel("Y"), c);
        c.gridx = 1;
        c.weightx = 1d;
        card.add(ySpinner, c);
        return card;
    }

    private JLabel plainLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(LABEL_TEXT);
        return label;
    }

    /** Spinners commit exact decimal coordinates; canvas clicks snap to cell centers. */
    private void addRoutePointCommit(JSpinner xSpinner, JSpinner ySpinner, boolean spawn) {
        String pointLabel = spawn ? "起点" : "终点";
        xSpinner.setToolTipText(pointLabel + " X 坐标（世界坐标，可为小数）");
        ySpinner.setToolTipText(pointLabel + " Y 坐标（世界坐标，可为小数）");
        javax.swing.event.ChangeListener commit = event -> {
            if (refreshing) {
                return;
            }
            UiPoint point = new UiPoint(floatValue(xSpinner), floatValue(ySpinner));
            try {
                runEdit(() -> {
                    if (spawn) {
                        session.placeSpawn(point);
                    } else {
                        session.placeEndpoint(point);
                    }
                });
            } catch (IllegalArgumentException error) {
                operationStatus.setText("无法移动" + pointLabel + "：" + error.getMessage());
                refresh(session.snapshotFrame());
            }
        };
        xSpinner.addChangeListener(commit);
        ySpinner.addChangeListener(commit);
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

    private JPanel createCombatSection() {
        JPanel section = section("战斗状态");
        section.setLayout(new GridBagLayout());

        JButton stunButton = ComponentIds.tag(new JButton("注入"), "B2", "眩晕注入");
        stunButton.setToolTipText("下一帧起眩晕指定秒数");
        stunButton.setFocusable(false);
        stunButton.addActionListener(event -> {
            commitSpinnerEdits(stunSecondsSpinner);
            try {
                session.applyStun(floatValue(stunSecondsSpinner));
                operationStatus.setText("已安排：下一帧起眩晕 " + floatValue(stunSecondsSpinner) + " 秒");
                // Refresh immediately so the slider max tracks a truncated timeline.
                refresh(session.snapshotFrame());
            } catch (RuntimeException error) {
                operationStatus.setText("无法眩晕：" + error.getMessage());
            }
        });
        addCombatRow(section, 0, "眩晕", stunSecondsSpinner, new JLabel("秒"), stunButton);

        JButton pushButton = ComponentIds.tag(new JButton("注入"), "B6", "击退注入");
        pushButton.setToolTipText("下一帧起以给定速度(格/秒)推动指定秒数");
        pushButton.setFocusable(false);
        pushButton.addActionListener(event -> {
            commitSpinnerEdits(pushXSpinner, pushYSpinner, pushSecondsSpinner);
            try {
                session.applyDisplacement(floatValue(pushXSpinner), floatValue(pushYSpinner),
                        floatValue(pushSecondsSpinner));
                operationStatus.setText("已安排：下一帧起击退 ("
                        + floatValue(pushXSpinner) + ", " + floatValue(pushYSpinner) + ") 持续 "
                        + floatValue(pushSecondsSpinner) + " 秒");
                refresh(session.snapshotFrame());
            } catch (RuntimeException error) {
                operationStatus.setText("无法击退：" + error.getMessage());
            }
        });
        // The row is split so three spinners never widen the sidebar: the
        // velocities sit next to 击退, the duration with its button below.
        addCombatRow(section, 1, "击退", pushXSpinner, pushYSpinner);
        addCombatRow(section, 2, "持续", pushSecondsSpinner, new JLabel("秒"), pushButton);

        bindToggle.setToolTipText("束缚期间单位不移动（下一帧起生效）");
        bindToggle.addActionListener(event -> {
            if (refreshing) {
                return;
            }
            try {
                session.setUnitBound(bindToggle.isSelected());
                operationStatus.setText(bindToggle.isSelected()
                        ? "已安排：下一帧起束缚" : "已安排：下一帧起解除束缚");
            } catch (RuntimeException error) {
                operationStatus.setText("无法束缚：" + error.getMessage());
            }
            // The toggle follows the session's display state: a scheduled intent
            // stays pressed; a rejected injection rolls the toggle back.
            refresh(session.snapshotFrame());
        });
        addCombatRow(section, 3, "束缚", bindToggle);
        return section;
    }

    /** One labeled combat row: label at gridx 0, controls flow-left at gridx 1. */
    private void addCombatRow(JPanel section, int row, String label, Component... fields) {
        GridBagConstraints c = baseConstraints();
        JLabel name = new JLabel(label);
        name.setForeground(LABEL_TEXT);
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0d;
        section.add(name, c);
        c.gridx = 1;
        c.weightx = 1d;
        JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        rowPanel.setOpaque(false);
        for (Component field : fields) {
            rowPanel.add(field);
        }
        section.add(rowPanel, c);
    }

    private JPanel createCheckpointSection() {
        JPanel section = section("检查点");
        section.setLayout(new BorderLayout(5, 5));

        JPanel editor = new JPanel(new GridBagLayout());
        editor.setOpaque(false);
        GridBagConstraints c = baseConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0d;
        editor.add(new JLabel("类型"), c);
        c.gridx = 1;
        c.weightx = 1d;
        checkpointTypeBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected,
                                                          boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof UiCheckpointType type) {
                    setText(type.label());
                }
                return this;
            }
        });
        editor.add(checkpointTypeBox, c);
        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0d;
        editor.add(new JLabel("参数"), c);
        c.gridx = 1;
        c.weightx = 1d;
        JPanel parameters = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        parameters.setOpaque(false);
        parameters.add(checkpointSecondsSpinner);
        parameters.add(checkpointAreaSpinner);
        editor.add(parameters, c);
        c.gridx = 0;
        c.gridy = 2;
        c.weightx = 0d;
        checkpointCoordLabel = new JLabel("坐标");
        editor.add(checkpointCoordLabel, c);
        c.gridx = 1;
        c.weightx = 1d;
        checkpointCoordPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        checkpointCoordPanel.setOpaque(false);
        checkpointCoordPanel.add(checkpointXSpinner);
        JLabel comma = new JLabel(",");
        comma.setForeground(LABEL_TEXT);
        checkpointCoordPanel.add(comma);
        checkpointCoordPanel.add(checkpointYSpinner);
        editor.add(checkpointCoordPanel, c);
        section.add(editor, BorderLayout.NORTH);

        checkpointList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        checkpointList.setVisibleRowCount(5);
        // The list must not dictate the sidebar width: a wide coordinate row
        // would grow the section past the viewport. The prototype fixes the
        // preferred width at a full-size entry (max map coordinate).
        checkpointList.setPrototypeCellValue(UiFormat.checkpointRow(98,
                new UiCheckpoint(UiCheckpointType.APPEAR_AT_POS, new UiPoint(512f, 512f), 0f, 0),
                false));
        // Selecting a checkpoint echoes its type and parameters into the panel,
        // so 更新 never overwrites with stale spinner values.
        checkpointList.addListSelectionListener(event -> {
            if (refreshing || event.getValueIsAdjusting()) {
                return;
            }
            echoCheckpointIntoPanel(checkpointList.getSelectedIndex());
        });
        checkpointListScroll = new JScrollPane(checkpointList);
        section.add(checkpointListScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(2, 3, 4, 4));
        actions.setOpaque(false);
        addCheckpointButton.setToolTipText("加入列表（选中项之前插入，未选中则加到末尾）");
        addCheckpointButton.addActionListener(event -> addCheckpointFromPanel());
        actions.add(addCheckpointButton);
        JButton update = ComponentIds.tag(new JButton("更新"), "C6", "更新选中的检查点");
        update.setFocusable(false);
        update.setToolTipText("把选中的检查点改为当前类型和参数（输入框里回车同效）");
        update.addActionListener(event -> updateSelectedCheckpoint());
        actions.add(update);
        // Enter inside any checkpoint editor field is the same as clicking 更新.
        for (JSpinner spinner : new JSpinner[]{checkpointSecondsSpinner, checkpointAreaSpinner,
                checkpointXSpinner, checkpointYSpinner}) {
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField()
                    .addActionListener(event -> updateSelectedCheckpoint());
        }
        JButton up = ComponentIds.tag(iconButton("↑", "上移选中的检查点"), "C8", "上移检查点");
        up.addActionListener(event -> moveSelectedCheckpoint(-1));
        actions.add(up);
        JButton down = ComponentIds.tag(iconButton("↓", "下移选中的检查点"), "C9", "下移检查点");
        down.addActionListener(event -> moveSelectedCheckpoint(1));
        actions.add(down);
        JButton remove = ComponentIds.tag(iconButton("−", "删除选中的检查点（Delete）"), "C10", "删除检查点");
        remove.addActionListener(event -> deleteSelectedCheckpoint());
        actions.add(remove);
        JButton clear = ComponentIds.tag(iconButton("×", "清空检查点"), "C11", "清空检查点");
        clear.addActionListener(event -> {
            try {
                runEdit(session::clearCheckpoints);
            } catch (IllegalArgumentException error) {
                operationStatus.setText("无法清空：" + error.getMessage());
            }
        });
        actions.add(clear);
        section.add(actions, BorderLayout.SOUTH);

        checkpointTypeBox.addActionListener(event -> refreshCheckpointControls());
        refreshCheckpointControls();
        return section;
    }

    private void bindConfigurationControls() {
        movementModeBox.addActionListener(event -> {
            if (!refreshing) {
                runEdit(() -> session.setMovementMode((UiMovementMode) movementModeBox.getSelectedItem()));
            }
        });
        speedSpinner.addChangeListener(event -> {
            if (!refreshing) {
                runEdit(() -> session.setAttributeSpeed(floatValue(speedSpinner)));
            }
        });
        diagonalToggle.addActionListener(event -> {
            if (!refreshing) {
                runEdit(() -> session.setAllowDiagonalMove(diagonalToggle.isSelected()));
            }
        });
    }

    private void bindTimelineControls() {
        timelineSlider.addChangeListener(event -> {
            if (refreshing) {
                return;
            }
            // Scrubbing pauses playback, matching the typed-time seek path.
            playback.stop();
            int target = timelineSlider.getValue();
            showTimelineSelection(target);
            if (!timelineSlider.getValueIsAdjusting()) {
                requestSeek(target);
            }
        });
        timeInput.addActionListener(event -> seekFromInput());
    }

    private void applyEditorTool(UiCell cell, UiPoint point) {
        String error = switch (selectedTool) {
            case OPEN -> terrainError(cell, UiTerrain.OPEN);
            case BOX -> terrainError(cell, UiTerrain.BOX);
            case PIT -> terrainError(cell, UiTerrain.PIT);
            case WALL -> terrainError(cell, UiTerrain.WALL);
            // Spawn/endpoint clicks snap to the clicked cell's center; exact
            // decimals are only accepted through the numeric editors.
            case SPAWN -> spawnError(cell.center());
            case ENDPOINT -> endpointError(cell.center());
            case CHECKPOINT -> checkpointError(point);
            case BROWSE -> null;
        };
        if (error != null) {
            canvas.flashRejection(cell);
            operationStatus.setText(error);
            return;
        }
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
    }

    /** Like terrainError/checkpointError: a refused placement reports why, never throws. */
    private String spawnError(UiPoint point) {
        try {
            session.placeSpawn(point);
            return null;
        } catch (IllegalArgumentException failure) {
            return "无法放置起点：" + failure.getMessage();
        }
    }

    private String endpointError(UiPoint point) {
        try {
            session.placeEndpoint(point);
            return null;
        } catch (IllegalArgumentException failure) {
            return "无法放置终点：" + failure.getMessage();
        }
    }

    private String terrainError(UiCell cell, UiTerrain value) {        return session.setTerrain(cell, value) ? null
                : "该格已被起点、终点或检查点占用，不能放置地形";
    }

    private String checkpointError(UiPoint point) {
        UiCheckpointType type = selectedCheckpointType();
        if (!type.hasPoint()) {
            return type.label() + "没有坐标，请用检查点面板的“添加”按钮";
        }
        try {
            session.addCheckpoint(newCheckpointOfType(type, point));
        } catch (IllegalArgumentException failure) {
            return "无法添加：" + failure.getMessage();
        }
        return null;
    }

    private UiCheckpointType selectedCheckpointType() {
        UiCheckpointType type = (UiCheckpointType) checkpointTypeBox.getSelectedItem();
        return type == null ? UiCheckpointType.MOVE : type;
    }

    private UiCheckpoint newCheckpointOfType(UiCheckpointType type, UiPoint point) {
        return type.create(point, floatValue(checkpointSecondsSpinner), intValue(checkpointAreaSpinner));
    }

    private void addCheckpointFromPanel() {
        UiCheckpointType type = selectedCheckpointType();
        if (type.hasPoint()) {
            operationStatus.setText("坐标类检查点请在地图上用 + 工具放置");
            return;
        }
        commitSpinnerEdits(checkpointSecondsSpinner, checkpointAreaSpinner);
        int index = checkpointList.getSelectedIndex();
        try {
            if (index >= 0) {
                runEdit(() -> session.insertCheckpointBefore(index, newCheckpointOfType(type, null)));
            } else {
                runEdit(() -> session.addCheckpoint(newCheckpointOfType(type, null)));
            }
        } catch (IllegalArgumentException error) {
            operationStatus.setText("无法添加：" + error.getMessage());
            return;
        }
        checkpointList.setSelectedIndex(index >= 0 ? index : checkpointModel.size() - 1);
    }

    private void updateSelectedCheckpoint() {
        int index = checkpointList.getSelectedIndex();
        if (index < 0) {
            operationStatus.setText("请先在列表中选择一个检查点");
            flashCheckpointListBorder();
            return;
        }
        commitSpinnerEdits(checkpointSecondsSpinner, checkpointAreaSpinner,
                checkpointXSpinner, checkpointYSpinner);
        try {
            UiCheckpointType type = selectedCheckpointType();
            UiPoint point = type.hasPoint()
                    ? new UiPoint(floatValue(checkpointXSpinner), floatValue(checkpointYSpinner))
                    : null;
            runEdit(() -> session.updateCheckpoint(index, type, point,
                    floatValue(checkpointSecondsSpinner), intValue(checkpointAreaSpinner)));
        } catch (IllegalArgumentException error) {
            operationStatus.setText("无法更新：" + error.getMessage());
            return;
        }
        checkpointList.setSelectedIndex(index);
    }

    /** Rejected updates flash the list frame so the hint has a visible anchor. */
    private void flashCheckpointListBorder() {
        if (checkpointListScroll == null) {
            return;
        }
        if (checkpointFlashTimer != null && checkpointFlashTimer.isRunning()) {
            checkpointFlashTimer.restart();
            return;
        }
        Border original = checkpointListScroll.getBorder();
        checkpointListScroll.setBorder(BorderFactory.createLineBorder(theme.rejection(), 2));
        checkpointFlashTimer = new javax.swing.Timer(600,
                event -> checkpointListScroll.setBorder(original));
        checkpointFlashTimer.setRepeats(false);
        checkpointFlashTimer.start();
    }

    /** Shared by the − button and the Delete/BackSpace shortcut. */
    private void deleteSelectedCheckpoint() {
        int index = checkpointList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        try {
            runEdit(() -> session.removeCheckpoint(index));
        } catch (IllegalArgumentException error) {
            operationStatus.setText("无法删除：" + error.getMessage());
        }
    }

    private void moveSelectedCheckpoint(int offset) {
        int index = checkpointList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        try {
            runEdit(() -> session.moveCheckpoint(index, offset));
        } catch (IllegalArgumentException error) {
            operationStatus.setText("无法移动：" + error.getMessage());
            return;
        }
        checkpointList.setSelectedIndex(Math.max(0, Math.min(checkpointModel.size() - 1, index + offset)));
    }

    private void stepOneFrame() {
        playback.stop();
        if (!session.canTick()) {
            operationStatus.setText("模拟已到达终态");
            return;
        }
        invalidateSeek();
        refresh(session.tickFrame());
    }

    private void resetRun() {
        playback.stop();
        invalidateSeek();
        session.resetSimulation();
        refresh(session.snapshotFrame());
    }

    private void undoEdit() {
        historyEdit(true);
    }

    private void redoEdit() {
        historyEdit(false);
    }

    /** Applies one undo or redo; scenario size changes refit the map view. */
    private void historyEdit(boolean isUndo) {
        playback.stop();
        invalidateSeek();
        int widthBefore = session.mapWidth();
        int heightBefore = session.mapHeight();
        boolean applied = isUndo ? session.undo() : session.redo();
        if (!applied) {
            operationStatus.setText(isUndo ? "没有可撤销的操作" : "没有可重做的操作");
            return;
        }
        refresh(session.snapshotFrame());
        if (session.mapWidth() != widthBefore || session.mapHeight() != heightBefore) {
            camera.requestFit();
        }
        operationStatus.setText(isUndo ? "已撤销" : "已重做");
    }

    private void bindKeyboardShortcuts() {
        InputMap keys = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actions = getRootPane().getActionMap();
        shortcut(keys, actions, "undo-edit", KeyStroke.getKeyStroke("control Z"), this::undoEdit);
        shortcut(keys, actions, "redo-edit", KeyStroke.getKeyStroke("control Y"), this::redoEdit);
        shortcut(keys, actions, "redo-edit", KeyStroke.getKeyStroke("control shift Z"), this::redoEdit);
        // Delete removes the armed checkpoint row; the typing guard keeps it
        // from firing while a text field is being edited.
        shortcut(keys, actions, "delete-checkpoint", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                this::deleteSelectedCheckpoint);
        shortcut(keys, actions, "delete-checkpoint", KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0),
                this::deleteSelectedCheckpoint);
        // Esc is the emergency brake during playback only.
        shortcut(keys, actions, "pause-playback", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                playback::stop);
        // Browser-style view controls.
        shortcut(keys, actions, "zoom-fit", KeyStroke.getKeyStroke("control 0"),
                camera::requestFit);
        shortcut(keys, actions, "zoom-in", KeyStroke.getKeyStroke("control EQUALS"),
                () -> zoomByKeyboard(1.25d));
        shortcut(keys, actions, "zoom-out", KeyStroke.getKeyStroke("control MINUS"),
                () -> zoomByKeyboard(1d / 1.25d));
    }

    /** Zooms around the viewport center; the camera's fit floor still applies. */
    private void zoomByKeyboard(double factor) {
        javax.swing.JViewport viewport = mapScrollPane.getViewport();
        Point view = viewport.getViewPosition();
        Point center = new Point(viewport.getWidth() / 2, viewport.getHeight() / 2);
        double worldX = canvas.worldXAtCanvas(view.x + center.x);
        double worldY = canvas.worldYAtCanvas(view.y + center.y);
        canvas.setZoomKeepingWorld(canvas.zoom() * factor, worldX, worldY, center, view);
        canvas.applyRequestedViewPosition();
        camera.refreshZoomLabel();
    }

    private static void shortcut(InputMap keys, ActionMap actions, String name,
                                  KeyStroke stroke, Runnable action) {
        keys.put(stroke, name);
        actions.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                // Bare keys (SPACE/N/R) must not fire while the user is typing.
                if (stroke.getModifiers() == 0 && !globalShortcutAllowed(
                        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                .getFocusOwner())) {
                    return;
                }
                action.run();
            }
        });
    }

    /** Global bare-key shortcuts yield to text entry; modified shortcuts (Ctrl+Z/Y) always fire. */
    static boolean globalShortcutAllowed(Component focusOwner) {
        return !(focusOwner instanceof javax.swing.text.JTextComponent);
    }

    /** Verify hook: the selection lists must never hold keyboard focus. */
    boolean verifyListFocusPolicy() {
        return !unitList.isFocusable() && !checkpointList.isFocusable();
    }

    /**
     * Verify hook: the new shortcuts act on the session — Delete removes the
     * armed checkpoint row and Esc pauses a running playback.
     */
    boolean verifyShortcutActions() {
        session.loadDemoScenario();
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        int checkpoints = session.snapshot().checkpoints().size();
        deleteSelectedCheckpoint();
        if (session.snapshot().checkpoints().size() != checkpoints - 1) {
            return false;
        }
        if (!playButton.isSelected()) {
            playButton.doClick();
        }
        playback.stop();
        return !playButton.isSelected();
    }

    /** Verify hook: Enter in a checkpoint editor field updates the selected row. */
    boolean verifyEnterUpdatesCheckpoint() {
        session.loadDemoScenario();
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        javax.swing.JFormattedTextField field =
                ((JSpinner.DefaultEditor) checkpointYSpinner.getEditor()).getTextField();
        field.setText("1.1222");
        for (java.awt.event.ActionListener listener : field.getActionListeners()) {
            listener.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, ""));
        }
        double y = session.snapshot().checkpoints().get(0).point().y();
        return Math.abs(y - 1.1222d) < 1e-6d;
    }

    /**
     * Verify hook: a scenario at the format's outer limits must leave every
     * editor spinner inside its model range, otherwise its step buttons die.
     */
    boolean verifyImportStaysInSpinnerRange() {
        session.importScenario("""
                # arknights pathfinding scenario v3
                map 100 100
                unit 1
                spawn 1 1
                endpoint 50 50
                movement GROUND
                speed 20
                diagonal true
                """);
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        return spinnerCanStep(speedSpinner)
                && spinnerCanStep(mapWidthSpinner)
                && spinnerCanStep(mapHeightSpinner);
    }

    private static boolean spinnerCanStep(JSpinner spinner) {
        return spinner.getModel() instanceof SpinnerNumberModel model && model.getNextValue() != null;
    }

    /**
     * Verify hook: the sidebar content must fit the viewport width it is
     * actually given. The old check only measured single spinners and missed
     * whole rows overflowing the fixed-width, no-hscroll sidebar.
     */
    boolean verifySidebarFits() {
        if (sidebarScrollPane == null) {
            return false;
        }
        Component view = sidebarScrollPane.getViewport().getView();
        int available = sidebarScrollPane.getViewport().getWidth();
        return available > 0 && view.getPreferredSize().width <= available;
    }

    /**
     * Verify hook: after every theme toggle the L&F-installed control colors
     * and our explicit chrome colors must agree with the resolved theme. The
     * L&F used to lag one preference-save behind and mix light with dark.
     */
    boolean verifyThemeToggleConsistency() {
        for (int i = 0; i < 3; i++) {
            themeToggleButton.doClick();
            boolean dark = theme == UiTheme.DARK;
            if (dark == isLightColor(checkpointList.getBackground())) {
                return false;
            }
            if (dark == isLightColor(addCheckpointButton.getBackground())) {
                return false;
            }
            if (!getContentPane().getBackground().equals(theme.windowBackground())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLightColor(Color color) {
        return color.getRed() + color.getGreen() + color.getBlue() > 384;
    }

    /** Screenshot-driver hook: advance one frame and repaint. */
    void sessionTickForShot() {
        session.tick();
        refresh(session.snapshotFrame());
    }

    /**
     * Verify hook: playback refreshes must preserve the checkpoint list
     * selection, otherwise 更新 is unusable while playing.
     */
    boolean verifyCheckpointSelectionSurvivesRefresh() {
        session.loadDemoScenario();
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        checkpointList.setSelectedIndex(1);
        session.tick();
        refresh(session.snapshotFrame());
        return checkpointList.getSelectedIndex() == 1;
    }

    /** Verify hook: refused spawn/endpoint placements report an error, never throw. */
    boolean verifyRoutePointRejectionFeedback() {
        UiPoint checkpointCell = session.snapshot().checkpoints().get(0).point();
        String spawnMessage = spawnError(checkpointCell);
        String endpointMessage = endpointError(session.snapshot().spawn());
        if (spawnMessage == null || endpointMessage == null) {
            return false;
        }
        // The demo spawn must be untouched by the refused moves above.
        return session.snapshot().spawn().x() == 1.5f && session.snapshot().spawn().y() == 1.5f;
    }

    /** Verify hook: the decimal checkpoint flow keeps exact coordinates end to end. */
    boolean verifyDecimalCheckpointFlow() {
        session.newScenario(8, 3);
        EditorTool previousTool = selectedTool;
        selectedTool = EditorTool.CHECKPOINT;
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        applyEditorTool(new UiCell(6, 1), new UiPoint(6.5f, 1.1222f));
        selectedTool = previousTool;
        List<UiCheckpoint> checkpoints = session.snapshot().checkpoints();
        if (checkpoints.size() != 1 || checkpoints.get(0).point() == null
                || Math.abs(checkpoints.get(0).point().y() - 1.1222f) > 0f) {
            return false;
        }
        String text = session.exportScenario();
        session.newScenario(8, 3);
        session.importScenario(text);
        selectedTool = EditorTool.OPEN;
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        return Math.abs(session.snapshot().checkpoints().get(0).point().y() - 1.1222f) <= 0f;
    }

    /** Verify hook: spawn/endpoint numeric rows only show for their own tool. */
    boolean verifyRoutePointEditorVisibility() {
        EditorTool previousTool = selectedTool;
        try {
            selectedTool = EditorTool.OPEN;
            updateRoutePointEditorVisibility();
            if (visibleRoutePointCardSpinner() != null) {
                return false;
            }
            selectedTool = EditorTool.SPAWN;
            updateRoutePointEditorVisibility();
            if (visibleRoutePointCardSpinner() != spawnXSpinner) {
                return false;
            }
            selectedTool = EditorTool.ENDPOINT;
            updateRoutePointEditorVisibility();
            return visibleRoutePointCardSpinner() == endpointXSpinner;
        } finally {
            selectedTool = previousTool;
            updateRoutePointEditorVisibility();
        }
    }

    /** The X spinner of the card the CardLayout shows, or null for the empty card. */
    private JSpinner visibleRoutePointCardSpinner() {
        for (Component component : routePointEditorSlot.getComponents()) {
            if (component.isVisible() && component instanceof JPanel card) {
                for (Component child : card.getComponents()) {
                    if (child instanceof JSpinner spinner) {
                        return spinner;
                    }
                }
            }
        }
        return null;
    }

    /** Verify hook: typed 4-decimal coordinates commit exactly through the spinner editor. */
    boolean verifyCoordinateSpinnerAcceptsDecimal() {
        javax.swing.JSpinner.NumberEditor editor =
                (javax.swing.JSpinner.NumberEditor) checkpointXSpinner.getEditor();
        editor.getTextField().setValue(1.1222d);
        try {
            checkpointXSpinner.commitEdit();
        } catch (java.text.ParseException failure) {
            return false;
        }
        double committed = ((Number) checkpointXSpinner.getValue()).doubleValue();
        checkpointXSpinner.setValue(0.5d);
        return Math.abs(committed - 1.1222d) < 1e-9;
    }

    /** Verify hook: every interactive control carries a unique inspector ID. */
    boolean verifyComponentIds() {
        java.util.Map<String, JComponent> seen = new java.util.LinkedHashMap<>();
        if (!collectComponentIds(getContentPane(), seen) || seen.size() < 50) {
            return false;
        }
        JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) checkpointXSpinner.getEditor();
        return "C1".equals(checkpointXSpinner.getName())
                && "C12".equals(checkpointList.getName())
                && "M0".equals(canvas.getName())
                && "P2".equals(playButton.getName())
                // A spinner's inner text field resolves to the tagged spinner itself.
                && ComponentIds.ownerOf(editor.getTextField()) == checkpointXSpinner
                && "检查点坐标 X".equals(ComponentIds.labelOf(checkpointXSpinner));
    }

    private static boolean collectComponentIds(java.awt.Container root,
                                               java.util.Map<String, JComponent> seen) {
        for (Component child : root.getComponents()) {
            if (ComponentIds.isTagged(child) && seen.put(child.getName(), (JComponent) child) != null) {
                return false;
            }
            if (child instanceof java.awt.Container container
                    && !collectComponentIds(container, seen)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Verify hook: a non-empty checkpoint list always keeps a selected row,
     * and the fresh selection echoes its real values into the panel.
     */
    boolean verifyCheckpointAutoSelected() {
        session.loadDemoScenario();
        playback.stop();
        invalidateSeek();
        checkpointList.clearSelection();
        refresh(session.snapshotFrame());
        if (checkpointList.getSelectedIndex() != 0) {
            return false;
        }
        double echoedX = ((Number) checkpointXSpinner.getValue()).doubleValue();
        double expected = session.snapshot().checkpoints().get(0).point().x();
        return Math.abs(echoedX - expected) < 1e-9;
    }

    /**
     * Verify hook: text typed but never committed (the 更新 button is
     * unfocusable, so it never blurs the field) is still applied by 更新.
     */
    boolean verifyUpdateCommitsPendingEditorText() {
        session.loadDemoScenario();
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        javax.swing.JFormattedTextField field =
                ((JSpinner.DefaultEditor) checkpointYSpinner.getEditor()).getTextField();
        field.setText("1.1222");
        updateSelectedCheckpoint();
        double y = session.snapshot().checkpoints().get(0).point().y();
        return Math.abs(y - 1.1222d) < 1e-6d;
    }

    /**
     * Verify hook: full-width IME characters normalize to ASCII while typing,
     * and ASCII typing is never rewritten mid-edit by the formatter.
     */
    boolean verifySpinnerTypingNormalization() {
        try {
            if (!typeIntoSpinner(checkpointYSpinner, "５。１２２２", "5.1222", 5.1222d)) {
                return false;
            }
            if (!typeIntoSpinner(checkpointXSpinner, "1.2222", "1.2222", 1.2222d)) {
                return false;
            }
            // Stepping after a typed commit must not throw: a Long/Double mix
            // in the model would surface here as a ClassCastException.
            Object next = ((SpinnerNumberModel) checkpointXSpinner.getModel()).getNextValue();
            return next instanceof Number number
                    && Math.abs(number.doubleValue() - 1.3222d) < 1e-9;
        } finally {
            checkpointXSpinner.setValue(0.5d);
            checkpointYSpinner.setValue(0.5d);
        }
    }

    /** Types one character at a time through the document filter, like a keyboard. */
    private static boolean typeIntoSpinner(JSpinner spinner, String typed, String expectedText,
                                           double expectedValue) {
        javax.swing.JFormattedTextField field =
                ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
        try {
            field.setText("");
            StringBuilder progressive = new StringBuilder();
            for (int i = 0; i < typed.length(); i++) {
                field.getDocument().insertString(field.getCaretPosition(),
                        typed.substring(i, i + 1), null);
                progressive.append(ImeNumericFilter.normalize(typed.substring(i, i + 1)));
                // The field must show exactly what was typed, never a rewrite.
                if (!field.getText().equals(progressive.toString())) {
                    return false;
                }
            }
            if (!field.getText().equals(expectedText)) {
                return false;
            }
            spinner.commitEdit();
        } catch (javax.swing.text.BadLocationException | java.text.ParseException failure) {
            return false;
        }
        double value = ((Number) spinner.getValue()).doubleValue();
        return Math.abs(value - expectedValue) < 1e-9;
    }

    /** Verify hook: 更新 without a selection flashes the list border and says why. */
    boolean verifyUpdateWithoutSelectionFlashesList() {
        session.newScenario(6, 3);
        playback.stop();
        invalidateSeek();
        refresh(session.snapshotFrame());
        checkpointList.clearSelection();
        updateSelectedCheckpoint();
        Border border = checkpointListScroll.getBorder();
        boolean flashed = border instanceof LineBorder line
                && line.getLineColor().equals(theme.rejection());
        return flashed && operationStatus.getText().contains("请先在列表中选择一个检查点");
    }

    // ----- theming -----------------------------------------------------------

    /** Theme preference: "system" follows the OS, "light"/"dark" pin it. */
    static String themePreference() {
        return Preferences.userNodeForPackage(SimulatorWorkbench.class).get("theme", "system");
    }

    /** The theme the UI should start with, honoring the OS when set to system. */
    static UiTheme resolvedTheme() {
        return switch (themePreference()) {
            case "dark" -> UiTheme.DARK;
            case "light" -> UiTheme.LIGHT;
            default -> systemPrefersDark() ? UiTheme.DARK : UiTheme.LIGHT;
        };
    }

    /** Windows exposes its app mode in the registry; other OSes read as light. */
    private static boolean systemPrefersDark() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            // AppsUseLightTheme 0 means dark, 1 means light; absent means light.
            return output.contains("AppsUseLightTheme") && output.contains("0x0");
        } catch (Exception failure) {
            return false;
        }
    }

    private void saveThemePreference() {
        Preferences.userNodeForPackage(SimulatorWorkbench.class).put("theme", themePreference);
    }

    /** Cycles 跟随系统 → 白天 → 黑夜, re-theming chrome and canvas together. */
    private void toggleTheme() {
        themePreference = switch (themePreference) {
            case "system" -> "light";
            case "light" -> "dark";
            default -> "system";
        };
        UiTheme previous = theme;
        theme = switch (themePreference) {
            case "dark" -> UiTheme.DARK;
            case "light" -> UiTheme.LIGHT;
            default -> systemPrefersDark() ? UiTheme.DARK : UiTheme.LIGHT;
        };
        switchLookAndFeel(theme);
        WINDOW_BACKGROUND = theme.windowBackground();
        PANEL_BACKGROUND = theme.panelBackground();
        BORDER = theme.border();
        VALUE = theme.valueText();
        LABEL_TEXT = theme.labelText();
        canvas.setTheme(theme);
        mapScrollPane.getViewport().setBackground(theme.canvasBackground());
        recolor(getContentPane(), previous);
        themeToggleButton.setText(themeIcon(themePreference));
        themeToggleButton.setToolTipText(themeTooltip(themePreference));
        saveThemePreference();
    }

    private static String themeIcon(String preference) {
        return switch (preference) {
            case "system" -> "自";
            case "dark" -> "☀";
            default -> "☾";
        };
    }

    private static String themeTooltip(String preference) {
        return switch (preference) {
            case "system" -> "跟随系统（点击切到白天）";
            case "dark" -> "黑夜（点击切到跟随系统）";
            default -> "白天（点击切到黑夜）";
        };
    }

    /**
     * Installs the FlatLaf variant for the theme we just resolved. Reading the
     * preference store here instead would lag one toggle behind, because the
     * new preference is only saved after the repaint.
     */
    private static void switchLookAndFeel(UiTheme target) {
        try {
            if (target == UiTheme.DARK) {
                com.formdev.flatlaf.FlatDarkLaf.setup();
            } else {
                com.formdev.flatlaf.FlatLightLaf.setup();
            }
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
            }
        } catch (Throwable missingFlatLaf) {
            // The L&F stays as-is; the canvas theme still switches.
        }
    }

    /** Remaps the colors we set ourselves, exact-match only — no guessing. */
    private void recolor(Component component, UiTheme previous) {
        if (component instanceof JComponent jc && !(component instanceof SimulationCanvas)) {
            remapBackground(jc, previous);
            remapForeground(jc, previous);
            remapBorder(jc, previous);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                recolor(child, previous);
            }
        }
    }

    private void remapBackground(JComponent jc, UiTheme previous) {
        Color bg = jc.getBackground();
        if (bg == null) {
            return;
        }
        if (bg.equals(previous.windowBackground())) {
            jc.setBackground(theme.windowBackground());
        } else if (bg.equals(previous.panelBackground())) {
            jc.setBackground(theme.panelBackground());
        } else if (bg.equals(previous.canvasBackground())) {
            jc.setBackground(theme.canvasBackground());
        }
    }

    private void remapForeground(JComponent jc, UiTheme previous) {
        Color fg = jc.getForeground();
        if (fg == null) {
            return;
        }
        if (fg.equals(previous.valueText())) {
            jc.setForeground(theme.valueText());
        } else if (fg.equals(previous.labelText())) {
            jc.setForeground(theme.labelText());
        }
    }

    private void remapBorder(JComponent jc, UiTheme previous) {
        Border remapped = remapBorder(jc.getBorder(), previous);
        if (remapped != jc.getBorder()) {
            jc.setBorder(remapped);
        }
    }

    private Border remapBorder(Border border, UiTheme previous) {
        if (border instanceof TitledBorder titled) {
            TitledBorder next = new TitledBorder(remapBorder(titled.getBorder(), previous), titled.getTitle());
            if (titled.getTitleColor() != null && titled.getTitleColor().equals(previous.valueText())) {
                next.setTitleColor(theme.valueText());
            }
            return next;
        }
        if (border instanceof LineBorder line && previous.border().equals(line.getLineColor())) {
            return BorderFactory.createLineBorder(theme.border());
        }
        if (border instanceof CompoundBorder compound) {
            return BorderFactory.createCompoundBorder(
                    remapBorder(compound.getOutsideBorder(), previous),
                    remapBorder(compound.getInsideBorder(), previous));
        }
        return border;
    }

    private void seekFromInput() {
        final long frame;
        try {
            frame = SimulationSession.parseTimeToFrame(timeInput.getText());
        } catch (IllegalArgumentException error) {
            operationStatus.setText(error.getMessage());
            timeInput.selectAll();
            return;
        }
        playback.stop();
        requestSeek(frame);
    }

    private void requestSeek(long frame) {
        if (frame < 0L || frame > Integer.MAX_VALUE - 1L) {
            operationStatus.setText("帧号超出范围");
            refresh(session.snapshotFrame());
            return;
        }
        long request = seekRequest.incrementAndGet();
        if (pendingSeek != null) {
            pendingSeek.cancel(true);
        }
        long revision = session.scenarioRevision();
        pendingSeek = seekExecutor.submit(() -> {
            try {
                UiFrame result = session.seekFrame(frame);
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
                        operationStatus.setText(error.getMessage());
                        refresh(session.snapshotFrame());
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
            UiFrame generated = session.generatedStateAtFrame(frame);
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

    private void refresh(UiFrame frame) {
        if (frame == null || frame.units().isEmpty()) {
            return;
        }
        UiSnapshot snapshot = frame.units().get(
                Math.min(session.selectedDraftIndex(), frame.units().size() - 1));
        refreshing = true;
        try {
            mapWidthSpinner.setValue(snapshot.width());
            mapHeightSpinner.setValue(snapshot.height());
            movementModeBox.setSelectedItem(snapshot.movementMode());
            speedSpinner.setValue((double) snapshot.attributeSpeed());
            diagonalToggle.setSelected(snapshot.allowDiagonalMove());
            spawnXSpinner.setValue((double) snapshot.spawn().x());
            spawnYSpinner.setValue((double) snapshot.spawn().y());
            endpointXSpinner.setValue((double) snapshot.endpoint().x());
            endpointYSpinner.setValue((double) snapshot.endpoint().y());
            canvas.setSnapshot(snapshot);
            canvas.setUnits(frame.units());
            canvas.setTrajectories(showTrajectoryToggle.isSelected()
                    ? trajectoriesThroughFrame(frame.frame()) : List.of());
            timelineSlider.setMaximum(Math.max(0, session.generatedLastFrame()));
            timelineSlider.setValue(Math.min(snapshot.frame(), timelineSlider.getMaximum()));
            undoButton.setEnabled(session.canUndo());
            redoButton.setEnabled(session.canRedo());
            frameValue.setText(Integer.toString(snapshot.frame()));
            timeValue.setText(SimulationSession.formatFrameTime(snapshot.frame()));
            sliderFrameValue.setText("帧 " + snapshot.frame());
            sliderTimeValue.setText(SimulationSession.formatFrameTime(snapshot.frame()));
            // Keep whatever the user is typing in the seek field; a later blur
            // or an actual seek refreshes it with the confirmed frame.
            if (!timeInput.hasFocus()) {
                timeInput.setText(Long.toString(snapshot.frame()));
            }
            modeValue.setText(snapshot.unitMode() + (snapshot.bound() ? "，束缚" : ""));
            bindToggle.setSelected(session.bindStateForDisplay());
            checkpointValue.setText(UiFormat.checkpointLabel(snapshot));
            positionValue.setText(UiFormat.point(snapshot.entityPosition()));
            velocityValue.setText(UiFormat.point(snapshot.inertiaVelocity()));
            avoidanceValue.setText(UiFormat.point(snapshot.avoidance())
                    + (snapshot.avoidanceRecomputed() ? "  刷新" : ""));
            targetValue.setText(UiFormat.point(snapshot.target()));
            nextNodeValue.setText(UiFormat.cell(snapshot.nextNode()));
            statusValue.setText(snapshot.transition().isBlank() ? "-" : snapshot.transition());
            // The simulation state channel: refresh() may always overwrite this.
            footerStatus.setText(UiFormat.statusLabel(snapshot));
            refreshUnitList();
            refreshCheckpointList(snapshot);
            refreshCheckpointControls();
            camera.refreshZoomLabel();
        } finally {
            refreshing = false;
        }
    }

    private void refreshUnitList() {
        unitModel.clear();
        for (int index = 0; index < session.unitCount(); index++) {
            unitModel.addElement("单位 " + (index + 1));
        }
        unitList.setSelectedIndex(session.selectedDraftIndex());
    }

    private List<List<UiSnapshot>> trajectoriesThroughFrame(int frame) {
        List<UiFrame> frames = session.generatedStates();
        int inclusiveCount = Math.min(frames.size(), Math.max(0, frame) + 1);
        List<List<UiSnapshot>> perUnit = new ArrayList<>();
        for (int unit = 0; unit < session.unitCount(); unit++) {
            List<UiSnapshot> path = new ArrayList<>(inclusiveCount);
            for (int index = 0; index < inclusiveCount; index++) {
                List<UiSnapshot> units = frames.get(index).units();
                path.add(units.get(Math.min(unit, units.size() - 1)));
            }
            perUnit.add(List.copyOf(path));
        }
        return List.copyOf(perUnit);
    }

    /** Copies the selected checkpoint's type and parameters into the editor panel. */
    private void echoCheckpointIntoPanel(int index) {
        if (index < 0 || index >= checkpointModel.size()) {
            return;
        }
        UiCheckpoint selected = session.snapshot().checkpoints().get(index);
        checkpointTypeBox.setSelectedItem(selected.type());
        checkpointSecondsSpinner.setValue((double) selected.value());
        checkpointAreaSpinner.setValue(selected.area());
        if (selected.point() != null) {
            checkpointXSpinner.setValue((double) selected.point().x());
            checkpointYSpinner.setValue((double) selected.point().y());
        }
    }

    private void refreshCheckpointList(UiSnapshot snapshot) {
        // Model rebuilds clear the JList selection (a clear() is a removal).
        // Remember and restore it so playback ticks never steal the user's
        // selected row; the echo listener above skips while refreshing.
        int selected = checkpointList.getSelectedIndex();
        checkpointModel.clear();
        for (int index = 0; index < snapshot.checkpoints().size(); index++) {
            UiCheckpoint checkpoint = snapshot.checkpoints().get(index);
            checkpointModel.addElement(UiFormat.checkpointRow(index, checkpoint,
                    index == snapshot.activeCheckpoint()));
        }
        if (selected >= 0 && selected < checkpointModel.size()) {
            checkpointList.setSelectedIndex(selected);
        } else if (!checkpointModel.isEmpty()) {
            // Always keep a row armed so 更新 has a target without an
            // easy-to-miss list click first. The echo listener skips while
            // refreshing, so typed spinner values are never clobbered; the
            // fresh selection echoes explicitly, or 更新 would write the
            // panel's factory defaults over the checkpoint's real values.
            checkpointList.setSelectedIndex(0);
            echoCheckpointIntoPanel(0);
        }
    }

    private void refreshCheckpointControls() {
        UiCheckpointType type = selectedCheckpointType();
        checkpointSecondsSpinner.setVisible(type.usesSeconds());
        checkpointAreaSpinner.setVisible(type.usesArea());
        if (checkpointCoordLabel != null) {
            checkpointCoordLabel.setVisible(type.hasPoint());
        }
        if (checkpointCoordPanel != null) {
            checkpointCoordPanel.setVisible(type.hasPoint());
        }
        addCheckpointButton.setEnabled(!type.hasPoint());
        addCheckpointButton.setToolTipText(type.hasPoint()
                ? "坐标类检查点请在地图上用 + 工具放置，或选中后用坐标+更新移动"
                : "加入列表（选中项之前插入，未选中则加到末尾）");
        java.awt.Container parameters = checkpointSecondsSpinner.getParent();
        if (parameters != null) {
            parameters.revalidate();
            parameters.repaint();
        }
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    /**
     * Sidebar stack that always matches the viewport width. Without this the
     * viewport hands the panel its preferred width, and with the horizontal
     * scrollbar disabled anything wider is silently clipped on the right.
     */
    private static final class SidebarContentPanel extends JPanel implements javax.swing.Scrollable {
        SidebarContentPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private JPanel section(String title) {
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

    private void addFormRow(JPanel panel, GridBagConstraints c, int row, String label, Component component) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0d;
        JLabel name = new JLabel(label);
        name.setForeground(LABEL_TEXT);
        panel.add(name, c);
        c.gridx = 1;
        c.weightx = 1d;
        panel.add(component, c);
    }

    private void addStatistic(JPanel panel, int row, String label, JLabel value) {
        GridBagConstraints c = baseConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0d;
        c.insets = new Insets(2, 0, 2, 7);
        JLabel name = new JLabel(label);
        name.setForeground(LABEL_TEXT);
        panel.add(name, c);
        c.gridx = 1;
        c.weightx = 1d;
        c.insets = new Insets(2, 0, 2, 0);
        panel.add(value, c);
    }

    private JLabel valueLabel() {
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
            updateRoutePointEditorVisibility();
        });
        if (tool == selectedTool) {
            button.setSelected(true);
        }
    }

    private static int intValue(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    private static float floatValue(JSpinner spinner) {
        return ((Number) spinner.getValue()).floatValue();
    }

    /** Keeps combat-row spinners narrow enough that the sidebar never clips horizontally. */
    private static JSpinner narrowSpinner(JSpinner spinner) {
        return cappedEditor(spinner, 2);
    }

    /**
     * Coordinate editors: 4-decimal display, typed values within range commit
     * as-is. Checkpoints get six columns ("1.2222" stays fully visible while
     * editing); the route-point editors take four because their rows share the
     * width with the section's label column.
     */
    private static JSpinner coordinateSpinner(int columns) {
        return cappedEditor(decimalSpinner(new SpinnerNumberModel(0.5d, 0.0d,
                (double) ScenarioCodec.MAXIMUM_DIMENSION, 0.1d)), columns);
    }

    /**
     * Decimal spinner with an explicit pattern editor. The default editor
     * (NumberEditorFormatter) commits on every valid keystroke and rewrites
     * the text mid-typing; a plain pattern-based NumberFormatter never does.
     */
    private static JSpinner decimalSpinner(SpinnerNumberModel model) {
        JSpinner spinner = new JSpinner(model);
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0.0###"));
        swapFormatter(spinner, "0.0###", Double.class);
        return spinner;
    }

    /** Integer spinner with the same no-rewrite, IME-safe editor as decimalSpinner. */
    private static JSpinner integerSpinner(SpinnerNumberModel model) {
        JSpinner spinner = new JSpinner(model);
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#0"));
        swapFormatter(spinner, "#0", Integer.class);
        return spinner;
    }

    /**
     * Installs the IME-normalizing formatter. The value class keeps parsed
     * numbers in the model's own type so min/max comparisons never mix Long
     * with Double and kill the step buttons.
     */
    private static void swapFormatter(JSpinner spinner, String pattern, Class<?> valueClass) {
        JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) spinner.getEditor();
        java.text.DecimalFormat format = new java.text.DecimalFormat(pattern,
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.getDefault()));
        ImeNumberFormatter formatter = new ImeNumberFormatter(format);
        formatter.setValueClass(valueClass);
        editor.getTextField().setFormatterFactory(
                new javax.swing.text.DefaultFormatterFactory(formatter));
    }

    /**
     * Tool buttons are unfocusable, so clicking one never blurs the spinner
     * the user is typing in and the commit-on-blur never fires. Commit any
     * pending editor text before an action reads the model; invalid text
     * reverts to the last committed value, matching focus-loss behavior.
     */
    private static void commitSpinnerEdits(JSpinner... spinners) {
        for (JSpinner spinner : spinners) {
            try {
                spinner.commitEdit();
            } catch (java.text.ParseException invalid) {
                ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField()
                        .setValue(spinner.getValue());
            }
        }
    }

    /** Caps the editor's column width so a huge model maximum never widens the sidebar. */
    private static JSpinner cappedEditor(JSpinner spinner, int columns) {
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setColumns(columns);
        }
        return spinner;
    }

    private enum EditorTool { OPEN, BOX, PIT, WALL, SPAWN, ENDPOINT, CHECKPOINT, BROWSE }

    private static final class SwatchIcon implements Icon {
        private final SimulationCanvas canvas;
        private final UiTerrain terrain;

        private SwatchIcon(SimulationCanvas canvas, UiTerrain terrain) {
            this.canvas = canvas;
            this.terrain = terrain;
        }

        @Override
        public int getIconWidth() { return 19; }

        @Override
        public int getIconHeight() { return 19; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setColor(this.canvas.terrainColor(terrain));
                canvas.fillRect(x, y, getIconWidth(), getIconHeight());
                canvas.setColor(new Color(128, 138, 132));
                canvas.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
            } finally {
                canvas.dispose();
            }
        }
    }
}
