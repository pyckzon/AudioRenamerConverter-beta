package com.casl.mp3converter.v2.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REGRESIÓN del reporte del usuario: "no se cierra el cuadro al dar click en
 * Activar contrato Pro". Causa probada con la sonda headless: un
 * {@code alert.showAndWait()} anidado DENTRO del resultConverter cuelga el
 * event loop y el OK_DONE nunca cierra. El patrón adoptado en
 * ActivationDialogV2: el converter SÓLO captura el resultado; la reacción
 * (alertas) se difiere a setOnHiding + runLater, fuera del ciclo de cierre.
 */
@Tag("v2.ui")
@DisplayName("Dialog OK_DONE: converter sin modales anidados -> cierra y reacciona diferido")
class DialogCloseRegressionTest {

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException yaIniciado) {
            // ya estaba
        }
    }

    @Test
    @DisplayName("REGRESIÓN: pulsar Activar cierra el diálogo y el hook difiere la reacción")
    void okDoneCierraYReaccionDiferida() throws Exception {
        CountDownLatch dialogoCerrado = new CountDownLatch(1);
        CountDownLatch reaccionCorrida = new CountDownLatch(1);
        AtomicReference<Throwable> fxError = new AtomicReference<>(null);

        Platform.runLater(() -> {
            try {
                Dialog<Void> dialog = new Dialog<>();
                ButtonType activar =
                        new ButtonType("Activar contrato Pro", ButtonBar.ButtonData.OK_DONE);
                dialog.getDialogPane().getButtonTypes().add(activar);

                AtomicReference<String> licenciaGenerada = new AtomicReference<>(null);

                // EXACTO el patrón de ActivationDialogV2: el converter NO abre modales,
                // sólo captura el estado de la activación:
                dialog.setResultConverter(buttonType -> {
                    if (buttonType == activar) {
                        licenciaGenerada.set("MPCL-estado-PRO");
                    }
                    return null;
                });

                // La reacción se difiere: onHiding + runLater (fuera del cierre):
                dialog.setOnHiding(event -> {
                    if (licenciaGenerada.get() != null) {
                        Platform.runLater(() -> reaccionCorrida.countDown());
                    }
                });

                dialog.showingProperty().addListener((obs, wasShowing, isShowing) -> {
                    if (wasShowing && !isShowing) {
                        dialogoCerrado.countDown();
                    }
                });

                dialog.show();
                Button activarButton = (Button) dialog.getDialogPane().lookupButton(activar);
                activarButton.fire();
            } catch (Throwable t) {
                fxError.set(t);
                reaccionCorrida.countDown();
                dialogoCerrado.countDown();
            }
        });

        boolean cerro = dialogoCerrado.await(8, TimeUnit.SECONDS);
        boolean reaccion = reaccionCorrida.await(8, TimeUnit.SECONDS);

        assertAll(
                () -> assertNull(fxError.get(), "sin excepciones en el FX thread: "
                        + fxError.get()),
                () -> assertTrue(cerro, "el diálogo DEBE cerrarse al pulsar Activar "
                        + "(el showAndWait anidado en el converter era lo que lo colgaba)"),
                () -> assertTrue(reaccion, "la reacción diferida (agradecimiento, alertas) "
                        + "corre tras el cierre, en un pulso propio del FX thread")
        );
    }
}
