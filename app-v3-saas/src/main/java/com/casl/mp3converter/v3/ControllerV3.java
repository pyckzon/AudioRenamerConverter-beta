package com.casl.mp3converter.v3;

import com.casl.mp3converter.core.Mp3ConversionService;
import com.casl.mp3converter.v3.saas.FeatureGate;
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
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller of MP3Converter SaaS v3.0.0 (fork de ControllerV2).
 * ALL tier rules live in {@link FeatureGate}: the controller only asks and reacts.
 * La diferencia con v2: el "Comprar versión Pro" será el flujo de SUSCRIPCIÓN
 * contra el backend SaaS (fase 9.3); por ahora el CTA explica el plan.
 */
public class ControllerV3 {
    private static final Logger LOGGER = Logger.getLogger(ControllerV3.class.getName());

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
    private javafx.scene.control.Label headerLabel;
    @FXML
    private Button btnBuyPro;

    private final ObjectProperty<Path> sourceDir = new SimpleObjectProperty<>();
    private final ObjectProperty<Path> destDir = new SimpleObjectProperty<>();
    private final BooleanProperty conversionRunning = new SimpleBooleanProperty(false);

    @FXML
    private void initialize() {
        headerLabel.setText(FeatureGate.headerTitle());
        versionLabel.setText(FeatureGate.footer());

        btnConvert.disableProperty().bind(
                conversionRunning.or(sourceDir.isNull()).or(destDir.isNull()));

        btnBuyPro.managedProperty().bind(btnBuyPro.visibleProperty());
        btnBuyPro.setVisible(!FeatureGate.isPro());

        progressBar.visibleProperty().bind(conversionRunning);
        progressBar.managedProperty().bind(conversionRunning);
        progressBar.setProgress(0.0);
    }

    /** Fase 9.3: abre el flujo real de suscripción (login → checkout web → token). */
    @FXML
    private void onBuyPro() {
        statusLabel.setText("Abriendo el flujo de suscripción...");
        com.casl.mp3converter.v3.ui.SubscriptionDialogV3.show(ownerWindow(), () -> {
            // Hot flip a PRO ya hecho por el diálogo; refresca la UI principal:
            refreshTierUi();
            statusLabel.setText("Suscripción activa: " + FeatureGate.lastReason());
        });
        statusLabel.setText("Listo.");
    }

    /** Refresca encabezado, pie, botón de compra y título según el tier VIGENTE. */
    private void refreshTierUi() {
        headerLabel.setText(FeatureGate.headerTitle());
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
                // El tier VIGENTE manda en cada conversión (hot flip, requisito 2.6):
                var service = FeatureGate.createService();
                return service.convert(source, dest, (converted, total, fileName) -> {
                    updateProgress(converted, total);
                    updateMessage(String.format("Convirtiendo %d de %d: %s", converted, total, fileName));
                });
            }
        };

        task.setOnSucceeded(event -> {
            conversionRunning.set(false);
            handleSummary(task.getValue());
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
        thread.setDaemon(true);
        thread.start();
    }

    private void handleSummary(Mp3ConversionService.ConversionSummary summary) {
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
            showBuyProDialog("Archivos que requieren la versión Pro",
                    String.join("\n", summary.skippedFiles()));
        } else if (!summary.skippedFiles().isEmpty()) {
            showFailedFilesDialog(summary);
        } else {
            showInformation("Proceso completado", text);
        }
    }

    /** CTA: detalles de los archivos bloqueados + "Suscribirse a la versión Pro". */
    private void showBuyProDialog(String title, String details) {
        try {
            var dialog = new Dialog<ButtonType>();
            var aceptar = new ButtonType("Aceptar", ButtonBar.ButtonData.OK_DONE);
            var suscribir = new ButtonType("Suscribirse a la versión Pro", ButtonBar.ButtonData.LEFT);
            dialog.setTitle(title);
            dialog.setHeaderText("La versión gratuita no convierte estos archivos:\n\n" + details);

            var notice = new TextArea(FeatureGate.buyNotice());
            notice.setEditable(false);
            notice.setWrapText(true);
            notice.setPrefHeight(150);
            VBox.setVgrow(notice, Priority.ALWAYS);
            dialog.getDialogPane().setContent(notice);

            dialog.getDialogPane().getButtonTypes().addAll(suscribir, aceptar);
            dialog.setResultConverter(buttonType -> buttonType);
            dialog.setResizable(true);
            dialog.getDialogPane().setMinWidth(520);
            dialog.initOwner(sourcePathLabel.getScene().getWindow());

            var pressed = dialog.showAndWait().orElse(aceptar);
            if (pressed == suscribir) {
                onBuyPro(); // 9.3: flujo real de suscripción
            }
        } catch (Exception e) { // Never let a dialog failure crash the application.
            LOGGER.log(Level.SEVERE, "No se pudo mostrar el CTA de compra", e);
            showWarning(title, details);
        }
    }

    /** Dialog for files that could not be converted (scrollable, non-editable). */
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
