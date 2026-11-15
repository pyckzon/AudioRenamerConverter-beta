package com.casl.mp3converter.v2.ui;

import com.casl.mp3converter.core.license.FieldCrypto;
import com.casl.mp3converter.core.license.LicenseController;
import com.casl.mp3converter.core.license.LicenseVerifier;
import com.casl.mp3converter.core.payment.PurchaseOutcome;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.function.Consumer;

/**
 * 2.6: cuadro emergente de activación tras la compra. Leyenda EXACTA del plan,
 * botón "copiar al portapapeles" y botón "activar contrato Pro" que llama a
 * {@link LicenseController#activate} (escribe el .lic junto al .exe).
 */
public final class ActivationDialogV2 {

    /** LEYENDA EXACTA del requisito 2.6 (probable copia como texto en el diálogo). */
    public static final String LEYENDA_EXACTA =
            "esta es tu clave de activación, puedes guardarla para usarla nuevamente "
                    + "en caso que quieras activar de nuevo tu mp3converter";

    private ActivationDialogV2() {
    }

    /**
     * Muestra el código con sus dos acciones. {@code onActivated} recibe el estado
     * tras la activación (la app v2 voltea FeatureGate en caliente con ella).
     */
    public static void show(Window owner, PurchaseOutcome outcome,
                            String correo, String apellido, Consumer<LicenseVerifier.LicenseState> onActivated) {
        var dialog = new Dialog<Void>();
        dialog.setTitle("Activación de MP3Converter Pro");
        dialog.setHeaderText(LEYENDA_EXACTA);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setResizable(true);

        Label codeLabel = new Label(outcome.activationCode());
        codeLabel.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 16px; "
                + "-fx-font-weight: bold;");

        ButtonType copiar = new ButtonType("Copiar al portapapeles", ButtonBar.ButtonData.LEFT);
        ButtonType activar = new ButtonType("Activar contrato Pro", ButtonBar.ButtonData.OK_DONE);
        // Sin botón "Cerrar" visible (a pedido del usuario). PERO JavaFX bloquea la
        // X de la barra de título si NO hay un ButtonType CANCEL_CLOSE — y el usuario
        // SÍ quiere poder cerrar con la X. Truco documentado incluir ese tipo OCULTO:
        ButtonType salirOculto = new ButtonType("Salir", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(activar, copiar, salirOculto);
        dialog.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                var botonSalir = dialog.getDialogPane().lookupButton(salirOculto);
                if (botonSalir != null) {
                    botonSalir.setVisible(false);
                    botonSalir.setManaged(false); // no ocupa espacio en la barra de botones
                }
            }
        });

        VBox content = new VBox(8);
        Label instruccion = new Label("Para pasar a PRO AHORA MISMO, pulsa el botón "
                + "«Activar contrato Pro» abajo: ahí se genera tu archivo "
                + "mp3converter.lic y la aplicación cambia a PRO sin reiniciar.");
        instruccion.setWrapText(true);
        instruccion.setStyle("-fx-font-weight: bold;");
        // Slot FIJO para el estado de la copia (REGRESIÓN: si el texto va en el
        // headerText, el encabezado crece al confirmar y el contenido se desplaza
        // ENCIMA de los botones). Con label fijo dentro del contenido no hay
        // ningún corrimiento de layout al copiar.
        Label estadoCopiado = new Label("Pulsa «Copiar al portapapeles» para guardar "
                + "la clave en el portapapeles del sistema.");
        estadoCopiado.setWrapText(true);
        estadoCopiado.setStyle("-fx-text-fill: #2e7d32;");
        content.getChildren().addAll(
                new Label("Clave de activación:"),
                codeLabel,
                instruccion,
                estadoCopiado,
                new Label("Sin base de datos, este código NO se recupera después: "
                        + "guárdalo ahora."));

        VBox.setMargin(codeLabel, new javafx.geometry.Insets(4, 0, 4, 12));
        // Espacio RESERVADO (pedido del usuario): altura mínima para toda la forma,
        // así nunca se enciman las letras con los botones.
        content.setMinHeight(280);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinHeight(360);

        // REGRESIÓN del reporte del usuario: copiar NO debe cerrar el diálogo.
        // En JavaFX CUALQUIER botón del Dialog cierra la ventana al pulsarlo; el
        // patrón documentado para "persistir" un botón (copiar/validar) es CONSUMIR
        // su evento Action: la copia va al portapapeles y la pantalla se conserva
        // hasta pulsar "Activar contrato Pro".
        var copiarButton = dialog.getDialogPane().lookupButton(copiar);
        copiarButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            var clipboardContent = new javafx.scene.input.ClipboardContent();
            clipboardContent.putString(outcome.activationCode());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(clipboardContent);
            estadoCopiado.setText("✔ Clave copiada al portapapeles. "
                    + "Puedes activar ahora mismo con «Activar contrato Pro».");
            event.consume(); // permanece abierto (2.6: copiar "hasta que le de en activar")
        });

        // Reacción FUERA del event loop del botón (ver DialogCloseProbe/regresión):
        // los showAndWait anidados dentro del resultConverter CUELGAN el event loop
        // y el diálogo no cierra. La activación SÓLO captura el resultado; las
        // alertas se disparan en setOnHiding (diálogo ya cerrado).
        var resultadoLicencia = new java.util.concurrent.atomic.AtomicReference<LicenseVerifier.LicenseState>(null);
        var errorActivacion = new java.util.concurrent.atomic.AtomicReference<Throwable>(null);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == activar) {
                try {
                    // 2.6: escribe el mp3converter.lic (formato MPCL, junto al .exe):
                    LicenseVerifier.LicenseState state = LicenseController.activate(
                            outcome.activationCode(), FieldCrypto.emailHash(correo));
                    if (onActivated != null) {
                        onActivated.accept(state);
                    }
                    resultadoLicencia.set(state);
                } catch (Exception e) {
                    // NADA silencioso: el despliegue de la alerta queda para onHiding
                    errorActivacion.set(e);
                }
            }
            return null;
        });

        // El botón OK_DONE cierra el dialog automáticamente tras el converter
        // (probado con la sonda). Incluso en setOnHiding seguimos dentro del
        // procesamiento del cierre: se difiere UN PULSO con runLater para que el
        // modal del agradecimiento/error abra 100% fuera del ciclo de cierre.
        dialog.setOnHiding(event -> {
            Throwable ex = errorActivacion.get();
            if (ex != null) {
                javafx.application.Platform.runLater(() -> errorAlert(owner, ex));
                return;
            }
            LicenseVerifier.LicenseState state = resultadoLicencia.get();
            if (state == null) {
                return; // se cerró sin activar (X): quedar en el tier vigente
            }
            if (state.tier() == LicenseVerifier.Tier.PRO) {
                // Agradecimiento EXACTO solicitado (header + content):
                javafx.application.Platform.runLater(() -> showThankYou(owner));
            } else {
                javafx.application.Platform.runLater(
                        () -> warnLicencia(owner, state.reason()));
            }
        });

        dialog.showAndWait();
    }

    /** Textos EXACTOS pedidos por el usuario tras activar correctamente. */
    private static void showThankYou(Window owner) {
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Licencia activada");
        alert.setHeaderText("Muchas gracias por adquirir la version PRO");
        alert.setContentText("Licencia activada!!! disfruta tu software!!!");
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    /** Fallo controlado (sin crash): lic no escrita — razón auditable en pantalla. */
    private static void warnLicencia(Window owner, String reason) {
        var alert = new Alert(Alert.AlertType.WARNING,
                "No se pudo escribir la licencia local: " + reason);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    /** Excepción al activar — visible SIEMPRE (ex .exe sin consola). */
    private static void errorAlert(Window owner, Throwable ex) {
        var alert = new Alert(Alert.AlertType.ERROR,
                "Error al activar la licencia: " + ex);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }
}
