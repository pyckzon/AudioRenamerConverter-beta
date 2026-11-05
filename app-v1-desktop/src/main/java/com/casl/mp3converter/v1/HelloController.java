package com.casl.mp3converter.v1;

import com.casl.mp3converter.core.Mp3ConversionService;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.ProgressBar;
import javafx.stage.DirectoryChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for the simple MP3 conversion UI:
 * <ol>
 *     <li>Select source folder</li>
 *     <li>Select destination folder</li>
 *     <li>Start the conversion process</li>
 * </ol>
 * A progress bar shows the progress and a dialog appears when the process finishes.
 * All user interactions are validated and every failure path shows an informative
 * dialog instead of crashing the application.
 */
public class HelloController {
    private static final Logger LOGGER = Logger.getLogger(HelloController.class.getName());

    @FXML
    private Button btnSource;
    @FXML
    private Button btnDestination;
    @FXML
    private Button btnConvert;
    @FXML
    private Label sourcePathLabel;
    @FXML
    private Label destinationPathLabel;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label statusLabel;

    private final ObjectProperty<Path> sourceDir = new SimpleObjectProperty<>();
    private final ObjectProperty<Path> destDir = new SimpleObjectProperty<>();
    private final BooleanProperty conversionRunning = new SimpleBooleanProperty(false);

    private final Mp3ConversionService conversionService = new Mp3ConversionService();

    @FXML
    private void initialize() {
        // Conversion is only possible when both folders are selected and nothing is running.
        btnConvert.disableProperty().bind(
                conversionRunning.or(sourceDir.isNull()).or(destDir.isNull()));

        // The progress bar is only relevant while converting.
        progressBar.visibleProperty().bind(conversionRunning);
        progressBar.managedProperty().bind(conversionRunning);
        progressBar.setProgress(0.0);
    }

    @FXML
    private void onSelectSourceFolder() {
        selectFolder("Selecciona la carpeta origen", sourceDir, sourcePathLabel);
    }

    @FXML
    private void onSelectDestinationFolder() {
        selectFolder("Selecciona la carpeta destino", destDir, destinationPathLabel);
    }

    private void selectFolder(String title, ObjectProperty<Path> target, Label pathLabel) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        Path initial = target.get();
        if (initial != null && Files.isDirectory(initial)) {
            chooser.setInitialDirectory(initial.toFile());
        }

        try {
            var selected = chooser.showDialog(pathLabel.getScene().getWindow());
            if (selected == null) {
                return; // User cancelled the dialog; nothing to do.
            }
            target.set(selected.toPath());
            pathLabel.setText(selected.getAbsolutePath());
            pathLabel.setStyle("-fx-text-fill: black;");
            statusLabel.setText("Listo.");
        } catch (Exception e) { // Defensive: a broken initial directory must not crash the app.
            LOGGER.log(Level.WARNING, "No se pudo abrir el selector de carpetas", e);
            showError("Error inesperado",
                    "No se pudo abrir el selector de carpetas:\n" + e.getMessage());
        }
    }

    @FXML
    private void onStartConversion() {
        Path source = sourceDir.get();
        Path dest = destDir.get();

        // --- Validations: never let the user run the process into a broken state. ---
        if (source == null || !Files.isDirectory(source)) {
            showWarning("Carpeta origen no válida",
                    "Selecciona primero una carpeta origen válida.");
            return;
        }
        if (dest == null || !Files.isDirectory(dest)) {
            showWarning("Carpeta destino no válida",
                    "Selecciona primero una carpeta destino válida.");
            return;
        }

        conversionRunning.set(true);
        statusLabel.setText("Iniciando conversión...");

        Task<Mp3ConversionService.ConversionSummary> task = new Task<>() {
            @Override
            protected Mp3ConversionService.ConversionSummary call() throws Exception {
                return conversionService.convert(source, dest, (converted, total, fileName) -> {
                    updateProgress(converted, total);
                    updateMessage(String.format("Convirtiendo %d de %d: %s", converted, total, fileName));
                });
            }
        };

        task.setOnSucceeded(event -> {
            conversionRunning.set(false);
            Mp3ConversionService.ConversionSummary summary = task.getValue();
            handleSummary(summary);
        });
        task.setOnCancelled(event -> {
            conversionRunning.set(false);
            statusLabel.setText("Conversión cancelada.");
            showInformation("Proceso cancelado",
                    "La conversión fue cancelada. Los archivos ya convertidos se conservan.");
        });
        task.setOnFailed(event -> {
            conversionRunning.set(false);
            Throwable error = task.getException();
            LOGGER.log(Level.SEVERE, "La conversión falló", error);
            statusLabel.setText("Error durante la conversión.");
            showError("Error durante la conversión",
                    buildErrorMessage(error));
        });

        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());

        Thread thread = new Thread(task, "mp3-conversion-thread");
        // Daemon so a stuck conversion never keeps the application alive.
        thread.setDaemon(true);
        thread.start();
    }

    private void handleSummary(Mp3ConversionService.ConversionSummary summary) {
        // Unbind so we can show a final value instead of the task's last update.
        progressBar.progressProperty().unbind();
        statusLabel.textProperty().unbind();

        if (summary.totalFiles() == 0) {
            showWarning("Sin archivos de audio",
                    "No se encontraron archivos de audio compatibles en la carpeta origen.\n\n"
                    + "Formatos soportados: " + Mp3ConversionService.SUPPORTED_EXTENSIONS_TEXT);
            statusLabel.setText("Sin archivos de audio compatibles.");
            return;
        }

        progressBar.setProgress(1.0);
        String text;
        if (summary.converted() == summary.totalFiles()) {
            text = String.format("Completado: %d archivo(s) convertidos.", summary.converted());
        } else {
            text = String.format("Completado: %d convertido(s), %d omitido(s).",
                    summary.converted(), summary.skipped());
        }
        statusLabel.setText(text);

        if (summary.skippedFiles().isEmpty()) {
            showInformation("Proceso completado", text);
        } else {
            showFailedFilesDialog(summary);
        }
    }

    /**
     * Dialog for the files that could not be converted: a scrollable, non-editable
     * text block (so long problem lists remain readable) with a single "Aceptar"
     * button to close it. Failure of the dialog itself never crashes the app.
     */
    private void showFailedFilesDialog(Mp3ConversionService.ConversionSummary summary) {
        try {
            var header = String.format("Convertidos: %d — Omitidos: %d. Detalle de los archivos con problemas:",
                    summary.converted(), summary.skipped());

            var details = new TextArea();
            details.setEditable(false);
            details.setWrapText(true);
            details.setText(String.join("\n", summary.skippedFiles()));
            // So the block gets the dialog's extra height instead of a fixed small box.
            VBox.setVgrow(details, Priority.ALWAYS);

            var dialog = new javafx.scene.control.Dialog<Void>();
            var aceptar = new javafx.scene.control.ButtonType("Aceptar",
                    javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            dialog.setTitle("Proceso completado con omisiones");
            dialog.setHeaderText(header);
            dialog.getDialogPane().setContent(details);
            dialog.getDialogPane().getButtonTypes().add(aceptar);
            dialog.setResizable(true);
            dialog.getDialogPane().setMinWidth(520);
            dialog.initOwner(sourcePathLabel.getScene().getWindow());
            dialog.showAndWait();
        } catch (Exception e) { // Fallback: very defensive, same spirit as showAlert.
            LOGGER.log(Level.SEVERE, "No se pudo mostrar el detalle de omisiones", e);
            showInformation("Proceso completado",
                    "Convertidos: " + summary.converted() + "\nOmitidos: " + summary.skipped());
        }
    }

    private String buildErrorMessage(Throwable error) {
        if (error instanceof Mp3ConversionService.ConversionServiceException) {
            // Known conditions get a friendly explanation.
            return error.getMessage();
        }
        return "Ocurrió un error inesperado:\n" + error
               + "\n\nRevisa la consola para más detalles.";
    }

    // --- Dialog helpers ---------------------------------------------------

    private void showInformation(String title, String content) {
        showAlert(Alert.AlertType.INFORMATION, title, content);
    }

    private void showWarning(String title, String content) {
        showAlert(Alert.AlertType.WARNING, title, content);
    }

    private void showError(String title, String content) {
        showAlert(Alert.AlertType.ERROR, title, content);
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        try {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.initOwner(sourcePathLabel.getScene().getWindow());
            alert.showAndWait();
        } catch (Exception e) { // Never let a dialog failure crash the application.
            LOGGER.log(Level.SEVERE, "No se pudo mostrar el diálogo " + title, e);
        }
    }
}
