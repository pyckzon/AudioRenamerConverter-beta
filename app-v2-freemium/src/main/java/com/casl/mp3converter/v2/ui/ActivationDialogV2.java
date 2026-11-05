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
        ButtonType cerrar = new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(activar, copiar, cerrar);

        VBox content = new VBox(8);
        content.getChildren().addAll(
                new Label("Clave de activación:"),
                codeLabel,
                new Label("Sin base de datos, este código NO se recupera después: "
                        + "guárdalo ahora."));

        VBox.setMargin(codeLabel, new javafx.geometry.Insets(4, 0, 4, 12));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == copiar) {
                // copia SIEMPRE accesible para el usuario (2.6):
                var contentClipboard = new javafx.scene.input.ClipboardContent();
                contentClipboard.putString(outcome.activationCode());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(contentClipboard);
                dialog.setHeaderText(LEYENDA_EXACTA + "\n\n✔ Clave copiada al portapapeles.");
                return null;
            }
            if (buttonType == activar) {
                try {
                    // 2.6: escribe el mp3converter.lic (formato MPCL, junto al .exe):
                    LicenseVerifier.LicenseState state = LicenseController.activate(
                            outcome.activationCode(), FieldCrypto.emailHash(correo));
                    if (onActivated != null) {
                        onActivated.accept(state);
                    }
                    if (state.tier() == LicenseVerifier.Tier.PRO) {
                        // Agradecimiento EXACTO solicitado: header + content, luego cerrar:
                        showThankYou(owner);
                        dialog.close();
                    } else {
                        var alert = new Alert(Alert.AlertType.WARNING,
                                "No se pudo escribir la licencia local: " + state.reason());
                        if (owner != null) {
                            alert.initOwner(owner);
                        }
                        alert.showAndWait();
                    }
                } catch (Exception e) {
                    // NADA silencioso: si la activación falla, el usuario lo ve:
                    var alert = new Alert(Alert.AlertType.ERROR,
                            "Error al activar la licencia: " + e);
                    if (owner != null) {
                        alert.initOwner(owner);
                    }
                    alert.showAndWait();
                }
            }
            return null;
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
}
