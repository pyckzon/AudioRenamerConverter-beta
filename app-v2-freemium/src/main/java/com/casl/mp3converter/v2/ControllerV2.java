package com.casl.mp3converter.v2;

import com.casl.mp3converter.core.Mp3ConversionService;
import com.casl.mp3converter.core.license.LicenseVerifier;
import com.casl.mp3converter.v2.freemium.FeatureGate;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.stage.Window;

/**
 * Controller of MP3Converter v2.0.0.
 * ALL tier rules live in {@link FeatureGate}: the controller only asks and reacts.
 * <p>
 * Free tier behaves per requirements: 92 kbps (2.1), audible "mp3Converter" watermark
 * at start and end (2.2), .opus listed but blocked with a "requires Pro" message and
 * a "Buy Pro" CTA dialog (2.3). Fase 5: compra real con formulario (2.5), pasarela
 * mock (2.5.1), persistencia cifrada (2.5.2) y activación en caliente (2.6).
 */
public class ControllerV2 {
    private static final Logger LOGGER = Logger.getLogger(ControllerV2.class.getName());

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
    @FXML
    private javafx.scene.control.Label versionLabel;
    @FXML
    private Button btnBuyPro;

    private final ObjectProperty<Path> sourceDir = new SimpleObjectProperty<>();
    private final ObjectProperty<Path> destDir = new SimpleObjectProperty<>();
    private final BooleanProperty conversionRunning = new SimpleBooleanProperty(false);

    @FXML
    private void initialize() {
        // El tier decide el servicio en CADA conversión (activación puede ser caliente):
        versionLabel.setText(FeatureGate.footer());

        // Conversion is only possible when both folders are selected and nothing is running.
        btnConvert.disableProperty().bind(
                conversionRunning.or(sourceDir.isNull()).or(destDir.isNull()));

        // El botón de compra solo se ve en FREE (tras activar Pro desaparece):
        btnBuyPro.managedProperty().bind(btnBuyPro.visibleProperty());
        btnBuyPro.setVisible(!FeatureGate.isPro());

        // The progress bar is only relevant while converting.
        progressBar.visibleProperty().bind(conversionRunning);
        progressBar.managedProperty().bind(conversionRunning);
        progressBar.setProgress(0.0);
    }

    /** Fase 5 (2.5/2.6): abre el formulario de pago desde el botón principal. */
    @FXML
    private void onBuyPro() {
        openBuyProDialog();
    }

    /** Abre el flujo de compra (diálogo 2.5) y encadena el diálogo de activación (2.6). */
    private void openBuyProDialog() {
        statusLabel.setText("Abriendo el formulario de pago...");
        com.casl.mp3converter.v2.ui.PaymentDialogV2.show(ownerWindow(), outcome -> {
            if (outcome != null && outcome.succeeded()) {
                showActivationDialog(outcome);
            }
        });
        statusLabel.setText("Listo.");
    }

    /** 2.6: cuadro de activación con leyenda exacta + copiar + "activar contrato Pro". */
    private void showActivationDialog(com.casl.mp3converter.core.payment.PurchaseOutcome outcome) {
        boolean persistido = outcome.codePersisted();
        com.casl.mp3converter.v2.ui.ActivationDialogV2.show(
                ownerWindow(),
                outcome,
                outcome.profile().correo(),
                outcome.profile().apellido(),
                state -> {
                    // Activación EN CALIENTE (requisito 2.6): sin reiniciar.
                    FeatureGate.setProEnabled(state.tier() == LicenseVerifier.Tier.PRO);
                    refreshTierUi();
                    if (!persistido) {
                        statusLabel.setText("Pro activado (código efímero: modo demo sin BD).");
                    }
                });
    }

    /** Refresca pie, botón de compra y título según el tier VIGENTE. */
    private void refreshTierUi() {
        versionLabel.setText(FeatureGate.footer());
        btnBuyPro.setVisible(!FeatureGate.isPro());
        try {
            var stage = (javafx.stage.Stage) ownerWindow();
            if (stage != null) {
                stage.setTitle(FeatureGate.isPro()
                        ? "Convertidor a MP3 (PRO)"
                        : "Convertidor a MP3 (versión gratuita)");
            }
        } catch (ClassCastException fallback) {
            LOGGER.warning("La ventana activa no es un Stage; título no actualizado.");
        }
    }

    private Window ownerWindow() {
        return sourcePathLabel.getScene() == null ? null : sourcePathLabel.getScene().getWindow();
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
                // El tier VIGENTE manda en cada conversión (activación caliente, requisito 2.6):
                var service = FeatureGate.createService();
                return service.convert(source, dest, (converted, total, fileName) -> {
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

        boolean needsProCta = summary.skippedFiles().stream().anyMatch(reason -> reason.contains("versión Pro"));

        if (needsProCta) {
            // 2.3: the .opus files appear with their reason and the CTA to buy the upgrade.
            showBuyProDialog("Archivos que requieren la versión Pro",
                    String.join("\n", summary.skippedFiles()));
        } else if (!summary.skippedFiles().isEmpty()) {
            showFailedFilesDialog(summary);
        } else {
            showInformation("Proceso completado", text);
        }
    }

    /** 2.3 CTA: details of the blocked files + "Comprar versión Pro" → flujo real (fase 5). */
    private void showBuyProDialog(String title, String details) {
        try {
            var dialog = new Dialog<ButtonType>();
            var aceptar = new ButtonType("Aceptar", ButtonBar.ButtonData.OK_DONE);
            var comprar = new ButtonType("Comprar versión Pro", ButtonBar.ButtonData.LEFT);
            dialog.setTitle(title);
            dialog.setHeaderText("La versión gratuita no convierte estos archivos:\n\n" + details);

            var notice = new TextArea(FeatureGate.buyNotice());
            notice.setEditable(false);
            notice.setWrapText(true);
            notice.setPrefHeight(150);
            VBox.setVgrow(notice, Priority.ALWAYS);
            dialog.getDialogPane().setContent(notice);

            dialog.getDialogPane().getButtonTypes().addAll(comprar, aceptar);
            dialog.setResultConverter(buttonType -> buttonType); // devuelve el botón presionado
            dialog.setResizable(true);
            dialog.getDialogPane().setMinWidth(520);
            dialog.initOwner(sourcePathLabel.getScene().getWindow());

            var pressed = dialog.showAndWait().orElse(aceptar);
            if (pressed == comprar) {
                // Fase 5: el CTA ya abre el flujo real de compra (2.5) + activación (2.6):
                openBuyProDialog();
            }
        } catch (Exception e) { // Never let a dialog failure crash the application.
            LOGGER.log(Level.SEVERE, "No se pudo mostrar el CTA de compra", e);
            showWarning(title, details);
        }
    }

    /**
     * Dialog for the files that could not be converted: a scrollable, non-editable
     * text block with a single "Aceptar" button to close it.
     */
    private void showFailedFilesDialog(Mp3ConversionService.ConversionSummary summary) {
        try {
            var header = String.format("Convertidos: %d — Omitidos: %d. Detalle de los archivos con problemas:",
                    summary.converted(), summary.skipped());

            var details = new TextArea();
            details.setEditable(false);
            details.setWrapText(true);
            details.setText(String.join("\n", summary.skippedFiles()));
            VBox.setVgrow(details, Priority.ALWAYS);

            var dialog = new Dialog<Void>();
            var aceptar = new javafx.scene.control.ButtonType("Aceptar",
                    ButtonBar.ButtonData.OK_DONE);
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
