import javax.swing.JFileChooser;
import javax.swing.JLabel;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Scenario and trace file dialogs plus the read/write plumbing. The session
 * owns the loaded content; the workbench is only told when an import landed
 * so it can resync playback and the view.
 */
final class ScenarioFiles {
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
        writeChosenFile("scenario.txt", session.exportScenario(), "场景已导出");
    }

    void exportTrace() {
        writeChosenFile("trace.csv", session.exportTraceCsv(), "轨迹已导出");
    }

    void importScenario() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String text;
        try {
            text = Files.readString(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8);
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

    private void writeChosenFile(String suggestedName, String content, String successLabel) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(suggestedName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            operationStatus.setText("导出失败：" + error.getMessage());
            return;
        }
        operationStatus.setText(successLabel + "：" + target.getFileName());
    }
}
