import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Scenario and trace file dialogs plus the read/write plumbing. The session
 * owns the loaded content; the workbench is only told when an import landed
 * so it can resync playback and the view.
 */
final class ScenarioFiles {
    /** UTF-8 byte-order mark so Excel opens the CSV without mojibake. */
    private static final String BOM = String.valueOf((char) 0xFEFF);

    private final SimulationSession session;
    private final Component parent;
    private final JLabel operationStatus;
    private final Runnable afterImport;

    ScenarioFiles(SimulationSession session, Component parent, JLabel operationStatus,
                  Runnable afterImport) {
        this.session = session;
        this.parent = parent;
        this.operationStatus = operationStatus;
        this.afterImport = afterImport;
    }

    void exportScenario() {
        writeChosenFile("scenario.txt", "txt", session.exportScenario(), false, "场景已导出");
    }

    void exportTrace() {
        // BOM so Excel opens the UTF-8 CSV without mojibake.
        writeChosenFile("trace.csv", "csv", session.exportTraceCsv(), true, "轨迹已导出");
    }

    void importScenario() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String text;
        try {
            text = stripBom(Files.readString(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            operationStatus.setText("导入失败：" + error.getMessage());
            return;
        }
        try {
            session.importScenario(text);
        } catch (IllegalArgumentException error) {
            operationStatus.setText("导入失败：" + error.getMessage());
            return;
        }
        afterImport.run();
        operationStatus.setText("场景已导入：" + chooser.getSelectedFile().getName());
    }

    private void writeChosenFile(String suggestedName, String extension, String content,
                                 boolean withBom, String successLabel) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(extension.toUpperCase(Locale.ROOT) + " 文件", extension));
        chooser.setSelectedFile(new File(suggestedName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = withExtension(chooser.getSelectedFile(), extension).toPath();
        if (Files.exists(target)) {
            int choice = JOptionPane.showConfirmDialog(parent,
                    "文件已存在，是否覆盖：" + target.getFileName(), "覆盖确认",
                    JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            Files.writeString(target, (withBom ? BOM : "") + content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            operationStatus.setText("导出失败：" + error.getMessage());
            return;
        }
        operationStatus.setText(successLabel + "：" + target.getFileName());
    }

    private static File withExtension(File file, String extension) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.substring(dot + 1).equalsIgnoreCase(extension)) {
            return file;
        }
        return new File(file.getParentFile(), name + "." + extension);
    }

    private static String stripBom(String text) {
        return text.startsWith(BOM) ? text.substring(BOM.length()) : text;
    }
}
