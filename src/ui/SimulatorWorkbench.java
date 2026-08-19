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
import javax.swing.JFileChooser;
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
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final JButton undoButton = iconButton("↶", "撤销上一步场景编辑（Ctrl+Z）");
    private final JButton redoButton = iconButton("↷", "重做（Ctrl+Y）");
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
    private final javax.swing.DefaultListModel<String> unitModel = new javax.swing.DefaultListModel<>();
    private final JList<String> unitList = new JList<>(unitModel);
    private final JComboBox<UiCheckpointType> checkpointTypeBox = new JComboBox<>(UiCheckpointType.values());
    private final JSpinner checkpointSecondsSpinner = new JSpinner(new SpinnerNumberModel(1.0d, 0.0d, 3600.0d, 0.1d));
    private final JSpinner checkpointAreaSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 100, 1));
    private final JButton addCheckpointButton = new JButton("添加");
    private final JSpinner stunSecondsSpinner = new JSpinner(new SpinnerNumberModel(1.0d, 0.0d, 60.0d, 0.1d));
    private final JSpinner pushXSpinner = new JSpinner(new SpinnerNumberModel(0.0d, -10.0d, 10.0d, 0.5d));
    private final JSpinner pushYSpinner = new JSpinner(new SpinnerNumberModel(0.0d, -10.0d, 10.0d, 0.5d));
    private final JSpinner pushSecondsSpinner = new JSpinner(new SpinnerNumberModel(0.5d, 0.0d, 10.0d, 0.1d));
    private final JToggleButton bindToggle = new JToggleButton("束缚");
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
        JButton step = iconButton("⏭", "推进一帧（N）");
        step.addActionListener(event -> stepOneFrame());
        toolbar.add(step);
        playButton.setToolTipText("运行（空格）");
        playButton.setFocusable(false);
        playButton.setPreferredSize(new Dimension(38, 29));
        playButton.addActionListener(event -> togglePlayback());
        toolbar.add(playButton);
        JButton reset = iconButton("↺", "重置运行（R）");
        reset.addActionListener(event -> resetRun());
        toolbar.add(reset);
        undoButton.addActionListener(event -> undoEdit());
        toolbar.add(undoButton);
        redoButton.addActionListener(event -> redoEdit());
        toolbar.add(redoButton);
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
        toolbar.addSeparator(new Dimension(10, 1));
        JButton exportScenarioButton = iconButton("导出场景", "把当前地图与路线保存为可导入的文本文件");
        exportScenarioButton.addActionListener(event -> exportScenarioFile());
        toolbar.add(exportScenarioButton);
        JButton importScenarioButton = iconButton("导入场景", "从文本文件载入地图与路线");
        importScenarioButton.addActionListener(event -> importScenarioFile());
        toolbar.add(importScenarioButton);
        JButton exportTraceButton = iconButton("导出轨迹", "把已生成帧导出为逐帧 CSV");
        exportTraceButton.addActionListener(event -> exportTraceFile());
        toolbar.add(exportTraceButton);
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
        content.add(createUnitSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createMovementSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createToolSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createRuntimeSection());
        content.add(Box.createVerticalStrut(8));
        content.add(createCombatSection());
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
            refresh(session.snapshotFrame());
            requestMapFit();
        });
        JButton demo = new JButton("示例");
        demo.addActionListener(event -> {
            stopPlayback();
            invalidateSeek();
            session.loadDemoScenario();
            refresh(session.snapshotFrame());
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

    private JPanel createUnitSection() {
        JPanel section = section("单位");
        section.setLayout(new BorderLayout(5, 5));
        unitList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        unitList.setVisibleRowCount(2);
        section.add(new JScrollPane(unitList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        JButton add = new JButton("添加");
        add.setToolTipText("复制当前路线为新单位");
        add.addActionListener(event -> {
            session.addDraft();
            stopPlayback();
            invalidateSeek();
            refresh(session.snapshotFrame());
        });
        actions.add(add);
        JButton remove = new JButton("删除");
        remove.setToolTipText("删除选中的单位（至少保留一个）");
        remove.addActionListener(event -> {
            int index = unitList.getSelectedIndex();
            if (index < 0) {
                return;
            }
            try {
                session.removeDraft(index);
            } catch (IllegalArgumentException error) {
                footerStatus.setText("无法删除：" + error.getMessage());
                return;
            }
            stopPlayback();
            invalidateSeek();
            refresh(session.snapshotFrame());
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

    private JPanel createCombatSection() {
        JPanel section = section("战斗状态");
        section.setLayout(new GridBagLayout());
        GridBagConstraints c = baseConstraints();

        JLabel stun = new JLabel("眩晕");
        stun.setForeground(new Color(94, 111, 101));
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0d;
        section.add(stun, c);
        c.gridx = 1;
        c.weightx = 1d;
        JPanel stunRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        stunRow.setOpaque(false);
        stunRow.add(stunSecondsSpinner);
        stunRow.add(new JLabel("秒"));
        JButton stunButton = new JButton("注入");
        stunButton.setToolTipText("下一帧起眩晕指定秒数");
        stunButton.addActionListener(event -> {
            try {
                session.applyStun(((Number) stunSecondsSpinner.getValue()).floatValue());
                footerStatus.setText("已安排：下一帧起眩晕 "
                        + ((Number) stunSecondsSpinner.getValue()).floatValue() + " 秒");
            } catch (RuntimeException error) {
                footerStatus.setText("无法眩晕：" + error.getMessage());
            }
        });
        stunRow.add(stunButton);
        section.add(stunRow, c);

        JLabel push = new JLabel("击退");
        push.setForeground(new Color(94, 111, 101));
        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0d;
        section.add(push, c);
        c.gridx = 1;
        c.weightx = 1d;
        JPanel pushRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        pushRow.setOpaque(false);
        pushRow.add(pushXSpinner);
        pushRow.add(pushYSpinner);
        pushRow.add(pushSecondsSpinner);
        pushRow.add(new JLabel("秒"));
        JButton pushButton = new JButton("注入");
        pushButton.setToolTipText("下一帧起以给定速度(格/秒)推动指定秒数");
        pushButton.addActionListener(event -> {
            try {
                session.applyDisplacement(
                        ((Number) pushXSpinner.getValue()).floatValue(),
                        ((Number) pushYSpinner.getValue()).floatValue(),
                        ((Number) pushSecondsSpinner.getValue()).floatValue());
                footerStatus.setText("已安排：下一帧起击退 ("
                        + ((Number) pushXSpinner.getValue()).floatValue() + ", "
                        + ((Number) pushYSpinner.getValue()).floatValue() + ") 持续 "
                        + ((Number) pushSecondsSpinner.getValue()).floatValue() + " 秒");
            } catch (RuntimeException error) {
                footerStatus.setText("无法击退：" + error.getMessage());
            }
        });
        pushRow.add(pushButton);
        section.add(pushRow, c);

        JLabel bind = new JLabel("束缚");
        bind.setForeground(new Color(94, 111, 101));
        c.gridx = 0;
        c.gridy = 2;
        c.weightx = 0d;
        section.add(bind, c);
        c.gridx = 1;
        c.weightx = 1d;
        bindToggle.setToolTipText("束缚期间单位不移动（下一帧起生效）");
        bindToggle.addActionListener(event -> {
            if (refreshing) {
                return;
            }
            try {
                session.setUnitBound(bindToggle.isSelected());
                footerStatus.setText(bindToggle.isSelected()
                        ? "已安排：下一帧起束缚" : "已安排：下一帧起解除束缚");
            } catch (RuntimeException error) {
                footerStatus.setText("无法束缚：" + error.getMessage());
            }
        });
        JPanel bindRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        bindRow.setOpaque(false);
        bindRow.add(bindToggle);
        section.add(bindRow, c);
        return section;
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
        section.add(editor, BorderLayout.NORTH);

        checkpointList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        checkpointList.setVisibleRowCount(5);
        section.add(new JScrollPane(checkpointList), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        addCheckpointButton.setToolTipText("加入列表（选中项之前插入，未选中则加到末尾）");
        addCheckpointButton.addActionListener(event -> addCheckpointFromPanel());
        actions.add(addCheckpointButton);
        JButton update = new JButton("更新");
        update.setToolTipText("把选中的检查点改为当前类型和参数");
        update.addActionListener(event -> updateSelectedCheckpoint());
        actions.add(update);
        JButton up = iconButton("↑", "上移选中的检查点");
        up.addActionListener(event -> moveSelectedCheckpoint(-1));
        actions.add(up);
        JButton down = iconButton("↓", "下移选中的检查点");
        down.addActionListener(event -> moveSelectedCheckpoint(1));
        actions.add(down);
        JButton remove = iconButton("−", "删除选中的检查点");
        remove.addActionListener(event -> {
            int index = checkpointList.getSelectedIndex();
            if (index < 0) {
                return;
            }
            try {
                session.removeCheckpoint(index);
            } catch (IllegalArgumentException error) {
                footerStatus.setText("无法删除：" + error.getMessage());
                return;
            }
            stopPlayback();
            invalidateSeek();
            refresh(session.snapshotFrame());
        });
        actions.add(remove);
        JButton clear = iconButton("×", "清空检查点");
        clear.addActionListener(event -> {
            try {
                session.clearCheckpoints();
            } catch (IllegalArgumentException error) {
                footerStatus.setText("无法清空：" + error.getMessage());
                return;
            }
            stopPlayback();
            invalidateSeek();
            refresh(session.snapshotFrame());
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
                stopPlayback();
                invalidateSeek();
                session.setMovementMode((UiMovementMode) movementModeBox.getSelectedItem());
                refresh(session.snapshotFrame());
            }
        });
        speedSpinner.addChangeListener(event -> {
            if (!refreshing) {
                stopPlayback();
                invalidateSeek();
                session.setAttributeSpeed(((Number) speedSpinner.getValue()).floatValue());
                refresh(session.snapshotFrame());
            }
        });
        diagonalToggle.addActionListener(event -> {
            if (!refreshing) {
                stopPlayback();
                invalidateSeek();
                session.setAllowDiagonalMove(diagonalToggle.isSelected());
                refresh(session.snapshotFrame());
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
        String error = switch (selectedTool) {
            case OPEN -> terrainError(cell, UiTerrain.OPEN);
            case BOX -> terrainError(cell, UiTerrain.BOX);
            case PIT -> terrainError(cell, UiTerrain.PIT);
            case WALL -> terrainError(cell, UiTerrain.WALL);
            case SPAWN -> {
                session.placeSpawn(cell);
                yield null;
            }
            case ENDPOINT -> {
                session.placeEndpoint(cell);
                yield null;
            }
            case CHECKPOINT -> checkpointError(cell);
            case BROWSE -> null;
        };
        if (error != null) {
            footerStatus.setText(error);
            return;
        }
        stopPlayback();
        invalidateSeek();
        refresh(session.snapshotFrame());
    }

    private String terrainError(UiCell cell, UiTerrain value) {
        return session.setTerrain(cell, value) ? null
                : "该格已被起点、终点或检查点占用，不能放置地形";
    }

    private String checkpointError(UiCell cell) {
        UiCheckpointType type = selectedCheckpointType();
        if (!type.hasPoint()) {
            return type.label() + "没有坐标，请用检查点面板的“添加”按钮";
        }
        try {
            session.addCheckpoint(newCheckpointOfType(type, cell));
        } catch (IllegalArgumentException failure) {
            return "无法添加：" + failure.getMessage();
        }
        return null;
    }

    private UiCheckpointType selectedCheckpointType() {
        UiCheckpointType type = (UiCheckpointType) checkpointTypeBox.getSelectedItem();
        return type == null ? UiCheckpointType.MOVE : type;
    }

    private UiCheckpoint newCheckpointOfType(UiCheckpointType type, UiCell cell) {
        float seconds = ((Number) checkpointSecondsSpinner.getValue()).floatValue();
        int area = ((Number) checkpointAreaSpinner.getValue()).intValue();
        return switch (type) {
            case MOVE -> UiCheckpoint.move(cell);
            case PATROL_MOVE -> UiCheckpoint.patrolMove(cell);
            case APPEAR_AT_POS -> UiCheckpoint.appearAt(cell);
            case WAIT_FOR_SECONDS -> UiCheckpoint.waitForSeconds(seconds);
            case WAIT_FOR_PLAY_TIME -> UiCheckpoint.waitForPlayTime(seconds);
            case WAIT_CURRENT_FRAGMENT_TIME -> UiCheckpoint.waitForFragmentTime(seconds);
            case WAIT_CURRENT_WAVE_TIME -> UiCheckpoint.waitForWaveTime(seconds);
            case WAIT_BOSSRUSH_WAVE -> UiCheckpoint.waitForBossRushArea(area);
            case DISAPPEAR -> UiCheckpoint.disappear();
            case ALERT -> UiCheckpoint.alert();
        };
    }

    private void addCheckpointFromPanel() {
        UiCheckpointType type = selectedCheckpointType();
        if (type.hasPoint()) {
            footerStatus.setText("坐标类检查点请在地图上用 + 工具放置");
            return;
        }
        int index = checkpointList.getSelectedIndex();
        try {
            if (index >= 0) {
                session.insertCheckpointBefore(index, newCheckpointOfType(type, null));
            } else {
                session.addCheckpoint(newCheckpointOfType(type, null));
            }
        } catch (IllegalArgumentException error) {
            footerStatus.setText("无法添加：" + error.getMessage());
            return;
        }
        stopPlayback();
        invalidateSeek();
        refresh(session.snapshotFrame());
        checkpointList.setSelectedIndex(index >= 0 ? index : checkpointModel.size() - 1);
    }

    private void updateSelectedCheckpoint() {
        int index = checkpointList.getSelectedIndex();
        if (index < 0) {
            footerStatus.setText("请先在列表中选择一个检查点");
            return;
        }
        try {
            session.updateCheckpoint(index, selectedCheckpointType(),
                    ((Number) checkpointSecondsSpinner.getValue()).floatValue(),
                    ((Number) checkpointAreaSpinner.getValue()).intValue());
        } catch (IllegalArgumentException error) {
            footerStatus.setText("无法更新：" + error.getMessage());
            return;
        }
        stopPlayback();
        invalidateSeek();
        refresh(session.snapshotFrame());
        checkpointList.setSelectedIndex(index);
    }

    private void moveSelectedCheckpoint(int offset) {
        int index = checkpointList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        try {
            session.moveCheckpoint(index, offset);
        } catch (IllegalArgumentException error) {
            footerStatus.setText("无法移动：" + error.getMessage());
            return;
        }
        stopPlayback();
        invalidateSeek();
        refresh(session.snapshotFrame());
        checkpointList.setSelectedIndex(Math.max(0, Math.min(checkpointModel.size() - 1, index + offset)));
    }

    private void stepOneFrame() {
        stopPlayback();
        if (!session.canTick()) {
            footerStatus.setText("模拟已到达终态");
            return;
        }
        invalidateSeek();
        refresh(session.tickFrame());
    }

    private void resetRun() {
        stopPlayback();
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
        stopPlayback();
        invalidateSeek();
        int widthBefore = session.mapWidth();
        int heightBefore = session.mapHeight();
        boolean applied = isUndo ? session.undo() : session.redo();
        if (!applied) {
            footerStatus.setText(isUndo ? "没有可撤销的操作" : "没有可重做的操作");
            return;
        }
        refresh(session.snapshotFrame());
        if (session.mapWidth() != widthBefore || session.mapHeight() != heightBefore) {
            requestMapFit();
        }
        footerStatus.setText(isUndo ? "已撤销" : "已重做");
    }

    private void bindKeyboardShortcuts() {
        InputMap keys = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actions = getRootPane().getActionMap();
        shortcut(keys, actions, "undo-edit", KeyStroke.getKeyStroke("control Z"), this::undoEdit);
        shortcut(keys, actions, "redo-edit", KeyStroke.getKeyStroke("control Y"), this::redoEdit);
        shortcut(keys, actions, "redo-edit", KeyStroke.getKeyStroke("control shift Z"), this::redoEdit);
        shortcut(keys, actions, "toggle-playback", KeyStroke.getKeyStroke("SPACE"), playButton::doClick);
        shortcut(keys, actions, "step-frame", KeyStroke.getKeyStroke('N'), this::stepOneFrame);
        shortcut(keys, actions, "reset-run", KeyStroke.getKeyStroke('R'), this::resetRun);
    }

    private static void shortcut(InputMap keys, ActionMap actions, String name,
                                  KeyStroke stroke, Runnable action) {
        keys.put(stroke, name);
        actions.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private void togglePlayback() {
        if (!playButton.isSelected()) {
            stopPlayback();
            return;
        }
        playButton.setText("Ⅱ");
        playButton.setToolTipText("暂停（空格）");
        playbackTimer.start();
    }

    private void advancePlayback() {
        UiFrame current = null;
        try {
            for (int index = 0; index < framesPerTimerTick(); index++) {
                current = session.tickFrame();
                if (session.isTerminal()) {
                    break;
                }
            }
        } catch (SimulationSession.TerminalStateException terminal) {
            stopPlayback();
        }
        refresh(current == null ? session.snapshotFrame() : current);
        if (current != null && session.isTerminal()) {
            stopPlayback();
        }
    }

    private void stopPlayback() {
        playbackTimer.stop();
        playButton.setSelected(false);
        playButton.setText("▶");
        playButton.setToolTipText("运行（空格）");
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

    private void exportScenarioFile() {
        writeChosenFile("scenario.txt", session.exportScenario(), "场景已导出");
    }

    private void exportTraceFile() {
        writeChosenFile("trace.csv", session.exportTraceCsv(), "轨迹已导出");
    }

    private void writeChosenFile(String suggestedName, String content, String successLabel) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(suggestedName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            footerStatus.setText("导出失败：" + error.getMessage());
            return;
        }
        footerStatus.setText(successLabel + "：" + target.getFileName());
    }

    private void importScenarioFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String text;
        try {
            text = Files.readString(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            footerStatus.setText("导入失败：" + error.getMessage());
            return;
        }
        try {
            session.importScenario(text);
        } catch (IllegalArgumentException error) {
            footerStatus.setText("导入失败：" + error.getMessage());
            return;
        }
        stopPlayback();
        invalidateSeek();
        refresh(session.snapshotFrame());
        requestMapFit();
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
            bindToggle.setSelected(snapshot.bound());
            checkpointValue.setText(checkpointLabel(snapshot));
            positionValue.setText(formatPoint(snapshot.entityPosition()));
            velocityValue.setText(formatPoint(snapshot.inertiaVelocity()));
            avoidanceValue.setText(formatPoint(snapshot.avoidance())
                    + (snapshot.avoidanceRecomputed() ? "  刷新" : ""));
            targetValue.setText(formatPoint(snapshot.target()));
            nextNodeValue.setText(formatCell(snapshot.nextNode()));
            statusValue.setText(snapshot.transition().isBlank() ? "-" : snapshot.transition());
            footerStatus.setText(statusLabel(snapshot));
            refreshUnitList();
            refreshCheckpointList(snapshot);
            refreshCheckpointControls();
            refreshZoomLabel();
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

    private void refreshCheckpointList(UiSnapshot snapshot) {
        checkpointModel.clear();
        for (int index = 0; index < snapshot.checkpoints().size(); index++) {
            UiCheckpoint checkpoint = snapshot.checkpoints().get(index);
            checkpointModel.addElement(String.format(Locale.ROOT, "%02d  %s %s",
                    index + 1, checkpoint.type().label(), checkpointDetail(checkpoint)).strip());
        }
        if (snapshot.activeCheckpoint() >= 0 && snapshot.activeCheckpoint() < checkpointModel.size()) {
            checkpointList.setSelectedIndex(snapshot.activeCheckpoint());
        }
    }

    private static String checkpointDetail(UiCheckpoint checkpoint) {
        if (checkpoint.cell() != null) {
            return String.format(Locale.ROOT, "(%d, %d)",
                    checkpoint.cell().x(), checkpoint.cell().y());
        }
        if (checkpoint.type().usesSeconds()) {
            return String.format(Locale.ROOT, "%.1f s", checkpoint.value());
        }
        if (checkpoint.type().usesArea()) {
            return checkpoint.area() + " 区";
        }
        return "";
    }

    private void refreshCheckpointControls() {
        UiCheckpointType type = selectedCheckpointType();
        checkpointSecondsSpinner.setVisible(type.usesSeconds());
        checkpointAreaSpinner.setVisible(type.usesArea());
        addCheckpointButton.setEnabled(!type.hasPoint());
        addCheckpointButton.setToolTipText(type.hasPoint()
                ? "坐标类检查点请在地图上用 + 工具放置"
                : "加入列表（选中项之前插入，未选中则加到末尾）");
        java.awt.Container parameters = checkpointSecondsSpinner.getParent();
        if (parameters != null) {
            parameters.revalidate();
            parameters.repaint();
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
