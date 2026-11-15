package com.casl.mp3converter.v3;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MP3Converter SaaS v3.0.0 — misma UI de conversión que v2, pero el tier
 * viene del backend SaaS por SUSCRIPCIÓN (Fase 9), no de una .lic local.
 */
public class ApplicationV3 extends Application {
    private static final Logger LOGGER = Logger.getLogger(ApplicationV3.class.getName());

    /** Fase 9.4: daemon que re-verifica la suscripción cada ~24 h. */
    private java.util.concurrent.ScheduledExecutorService heartbeatDaemon;

    @Override
    public void init() {
        // Fase 9.2/9.4: el tier arranca desde la sesión local + heartbeat
        // (suscripción ACTIVE + gracia offline de 7 días). Nunca lanza.
        try {
            com.casl.mp3converter.v3.saas.FeatureGate.refreshAtStartup();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "No se pudo verificar la suscripción: modo gratuito", e);
        }
        // y queda el daemon verificando en el fondo (ACTIVE/INACTIVE/EXPIRED):
        heartbeatDaemon = com.casl.mp3converter.v3.saas.FeatureGate.startHeartbeatDaemon();

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
            FXMLLoader fxmlLoader = new FXMLLoader(ApplicationV3.class.getResource("hello-view.fxml"));
            Scene scene = new Scene(fxmlLoader.load(), 480, 340);
            stage.setTitle(com.casl.mp3converter.v3.saas.FeatureGate.isPro()
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

    @Override
    public void stop() {
        // Fase 9.4: el daemon de heartbeat es hilo daemon (no frena el cierre),
        // pero se apaga limpio para no dejar el socket a medias:
        if (heartbeatDaemon != null) {
            heartbeatDaemon.shutdownNow();
        }
    }

    /**
     * Sets the application icon on the window and every child window that opens.
     * Failure to load the icon must never block the application.
     */
    private void applyWindowIcon(Stage stage) {
        try {
            var iconUrl = ApplicationV3.class.getResource("nuevo-logo.png");
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
