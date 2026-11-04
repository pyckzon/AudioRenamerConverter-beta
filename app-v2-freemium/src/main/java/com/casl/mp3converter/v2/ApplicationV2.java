package com.casl.mp3converter.v2;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** MP3Converter Free v2.0.0 — mismisma UI de la v1, reglas del tier controladas por {@code FeatureGate}. */
public class ApplicationV2 extends Application {
    private static final Logger LOGGER = Logger.getLogger(ApplicationV2.class.getName());

    @Override
    public void init() {
        // Fase 3 (requisito 2.4): el tier arranca desde el binario de licencia junto al .exe.
        // Fallido/corrupto/ausente siempre degrada a FREE (nunca lanza).
        try {
            com.casl.mp3converter.v2.freemium.FeatureGate.refreshFromLicenseFile();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "No se pudo leer la licencia: se continúa en modo gratuito", e);
        }

        // Last-resort safety net: log and warn instead of crashing silently.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            LOGGER.log(Level.SEVERE, "Error no controlado en " + thread.getName(), error);
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Error inesperado");
                alert.setHeaderText(null);
                alert.setContentText("Ocurrió un error inesperado:\n" + error);
                alert.showAndWait();
            });
        });
    }

    @Override
    public void start(Stage stage) {
        try {
            FXMLLoader fxmlLoader = new FXMLLoader(ApplicationV2.class.getResource("hello-view.fxml"));
            Scene scene = new Scene(fxmlLoader.load(), 480, 340);
            stage.setTitle(com.casl.mp3converter.v2.freemium.FeatureGate.isPro()
                    ? "Convertidor a MP3 (PRO)"
                    : "Convertidor a MP3 (versión gratuita)");
            applyWindowIcon(stage);
            stage.setScene(scene);
            stage.show();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "No se pudo cargar la interfaz", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error al iniciar");
            alert.setHeaderText(null);
            alert.setContentText("No se pudo cargar la interfaz de la aplicación:\n" + e);
            alert.showAndWait();
            Platform.exit();
        }
    }

    /**
     * Sets the application icon on the window and every child window that opens.
     * Failure to load the icon must never block the application.
     */
    private void applyWindowIcon(Stage stage) {
        try {
            var iconUrl = ApplicationV2.class.getResource("nuevo-logo.png");
            if (iconUrl == null) {
                LOGGER.warning("No se encontró el ícono de la ventana (nuevo-logo.png).");
                return;
            }
            stage.getIcons().add(new javafx.scene.image.Image(iconUrl.toExternalForm()));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "No se pudo aplicar el ícono de la ventana", e);
        }
    }
}
