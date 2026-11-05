package com.casl.mp3converter.v2.ui;

import com.casl.mp3converter.core.db.CustomerProfile;
import com.casl.mp3converter.core.payment.PaymentRequest;
import com.casl.mp3converter.core.payment.PaymentValidator;
import com.casl.mp3converter.core.payment.PurchaseOutcome;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 2.5: formulario modal de pago con las validaciones EXACTAS del plan, mensaje
 * inline por campo y el botón "Pagar" habilitado SOLO cuando todo es válido.
 * Al aprobar el mock (2.5.1) persiste cifrado (2.5.2) y entrega el resultado a la
 * UI para abrir el cuadro de activación (2.6).
 * <p>
 * El cobro corre DENTRO del modal (la pasarela mock simula su latencia con el
 * cursor de espera); la pasarela real (fase futura) hará lo mismo async.
 */
public final class PaymentDialogV2 {

    private PaymentDialogV2() {
    }

    /**
     * Abre el diálogo; {@code onPurchased} se invoca SOLO con
     * {@link PurchaseOutcome.Status#PURCHASED} (lleva el código en claro).
     */
    public static void show(Window owner, Consumer<PurchaseOutcome> onPurchased) {
        var dialog = new Dialog<PurchaseOutcome>();
        dialog.setTitle("Comprar la versión Pro");
        dialog.setHeaderText("Con Pro: calidad máxima, sin sello audible «mp3Converter» "
                + "y conversión de archivos .opus.");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setResizable(true);

        var pagar = new ButtonType("Pagar", ButtonBar.ButtonData.OK_DONE);
        var cancelar = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(pagar, cancelar);

        // --- campos -----------------------------------------------------------------
        TextField nombre = new TextField();
        TextField apellido = new TextField();
        TextField correo = new TextField();
        TextField telefono = new TextField();
        telefono.setPromptText("10 dígitos");
        ComboBox<String> country = DialogsSupport.buildCountryCombo();
        TextField direccion = new TextField();
        direccion.setPromptText("Calle y número, incluyendo #");
        TextField tarjeta = new TextField();
        tarjeta.setPromptText("4111 1111 1111 1111");
        TextField vencimiento = new TextField();
        vencimiento.setPromptText("mm/AAAA");
        TextField cvv = new TextField();
        cvv.setPromptText("3 dígitos");

        // --- formato dinámico mientras se escribe (requisito 2.5) --------------------
        tarjeta.textProperty().addListener((obs, oldV, newV) ->
                tariffSafeInsert(tarjeta, DialogsSupport.formatCardVisual(newV)));
        telefono.textProperty().addListener((obs, oldV, newV) ->
                tariffSafeInsert(telefono, DialogsSupport.digitsOnly(newV)));
        cvv.textProperty().addListener((obs, oldV, newV) -> {
            String digits = DialogsSupport.digitsOnly(newV);
            tariffSafeInsert(cvv, digits.length() > 3 ? digits.substring(0, 3) : digits);
        });
        vencimiento.textProperty().addListener((obs, oldV, newV) ->
                tariffSafeInsert(vencimiento, DialogsSupport.formatExpiryVisual(newV)));

        // --- errores inline por campo ------------------------------------------------
        Label errNombre = errorLabel();
        Label errApellido = errorLabel();
        Label errCorreo = errorLabel();
        Label errTelefono = errorLabel();
        Label errDireccion = errorLabel();
        Label errTarjeta = errorLabel();
        Label errVencimiento = errorLabel();
        Label errCvv = errorLabel();

        GridPane grid = gridWithFields(
                nombre, errNombre, apellido, errApellido, correo, errCorreo,
                country, telefono, errTelefono, direccion, errDireccion,
                tarjeta, errTarjeta, vencimiento, errVencimiento, cvv, errCvv);

        Label nota = new Label("Los datos de la tarjeta se usan SÓLO para la pasarela: "
                + "nunca se almacenan, ni siquiera en los logs.");
        nota.setStyle("-fx-text-fill: gray; -fx-font-size: 10px;");
        nota.setWrapText(true);

        VBox content = new VBox(6, grid, nota);
        dialog.getDialogPane().setContent(content);

        // --- "Pagar" habilitado SOLO cuando el formulario es 100% válido ---------------
        Runnable refresh = () -> {
            Map<String, String> errors = currentErrors(nombre, apellido, correo, telefono,
                    direccion, country, tarjeta, vencimiento, cvv);
            showInline(errNombre, errors.get(PaymentValidator.FIELD_NOMBRE));
            showInline(errApellido, errors.get(PaymentValidator.FIELD_APELLIDO));
            showInline(errCorreo, errors.get(PaymentValidator.FIELD_CORREO));
            showInline(errTelefono, errors.get(PaymentValidator.FIELD_TELEFONO));
            showInline(errDireccion, errors.get(PaymentValidator.FIELD_DIRECCION));
            showInline(errTarjeta, errors.get(PaymentValidator.FIELD_TARJETA));
            showInline(errVencimiento, errors.get(PaymentValidator.FIELD_VENCIMIENTO));
            showInline(errCvv, errors.get(PaymentValidator.FIELD_CVV));
            // el botón se resuelve en caliente (lookupButton cuelga hasta que la UI tiene skin):
            var pagarBtn = (javafx.scene.control.Button) dialog.getDialogPane().lookupButton(pagar);
            if (pagarBtn != null) {
                pagarBtn.setDisable(!errors.isEmpty());
            }
        };
        java.util.List.of(nombre, apellido, correo, telefono, direccion, tarjeta, vencimiento, cvv)
                .forEach(field -> field.textProperty().addListener((obs, oldV, newV) -> refresh.run()));
        country.valueProperty().addListener((obs, oldV, newV) -> refresh.run());
        dialog.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (isShowing) {
                Platform.runLater(refresh::run);
            }
        });

        // --- Pagar: valida/cobra/persiste y devuelve el resultado a la UI ---------------
        dialog.setResultConverter(buttonType -> {
            if (buttonType != pagar) {
                return null;
            }
            String phoneDigits = DialogsSupport.digitsOnly(telefono.getText());
            CustomerProfile profile = PurchaseContext.profileFrom(nombre.getText(),
                    apellido.getText(), correo.getText(),
                    DialogsSupport.countryCodeOf(country), phoneDigits);
            PaymentRequest request = new PaymentRequest(
                    (nombre.getText().strip() + " " + apellido.getText().strip()).strip(),
                    tarjeta.getText(),
                    PaymentValidator.validateExpiry(vencimiento.getText()).orElse(""),
                    PaymentValidator.validateCvv(cvv.getText()).orElse(""));

            PurchaseOutcome outcome = PurchaseContext.createFlow()
                    .purchase(profile, direccion.getText(), request);

            if (outcome.status() == PurchaseOutcome.Status.PURCHASED) {
                dialog.close();
                onPurchased.accept(outcome);
            } else if (outcome.status() == PurchaseOutcome.Status.PAYMENT_REJECTED) {
                errorDialog(owner, "La pasarela rechazó el cobro", outcome.gatewayReference());
            }
            return outcome;
        });

        dialog.showAndWait();
    }

    /** Inserta texto formato-dinámico solo si es distinto (evita reentrada del listener). */
    private static void tariffSafeInsert(TextField field, String formatted) {
        if (!formatted.equals(field.getText())) {
            field.setText(formatted);
        }
    }

    /** Errores actuales según el PaymentValidator del core (MISMAS reglas exactas). */
    private static Map<String, String> currentErrors(TextField nombre, TextField apellido,
                                                     TextField correo, TextField telefono,
                                                     TextField direccion, ComboBox<String> country,
                                                     TextField tarjeta, TextField vencimiento,
                                                     TextField cvv) {
        var profile = new CustomerProfile(nombre.getText(), apellido.getText(),
                correo.getText(), DialogsSupport.digitsOnly(telefono.getText()));
        var request = new PaymentRequest("", tarjeta.getText(), vencimiento.getText(), cvv.getText());
        return PaymentValidator.validateForm(profile, direccion.getText(), request);
    }

    /**
     * Grid: cada fila usa dos renglones (label, campo, y debajo el mensaje inline).
     * +52 México por default (requisito 2.5).
     */
    private static GridPane gridWithFields(TextField nombre, Label errNombre,
                                           TextField apellido, Label errApellido,
                                           TextField correo, Label errCorreo,
                                           ComboBox<String> country, TextField telefono, Label errTelefono,
                                           TextField direccion, Label errDireccion,
                                           TextField tarjeta, Label errTarjeta,
                                           TextField vencimiento, Label errVencimiento,
                                           TextField cvv, Label errCvv) {
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(2);
        grid.setPadding(new Insets(10));
        var wide = new javafx.scene.layout.ColumnConstraints();
        wide.setPrefWidth(320);
        grid.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(110), wide);

        addRow(grid, 0, "Nombre", nombre, errNombre);
        addRow(grid, 2, "Apellido", apellido, errApellido);
        addRow(grid, 4, "Correo", correo, errCorreo);
        addRow(grid, 6, "País", country, null);
        addRow(grid, 8, "Teléfono", telefono, errTelefono);
        addRow(grid, 10, "Dirección", direccion, errDireccion);
        addRow(grid, 12, "Tarjeta", tarjeta, errTarjeta);
        addRow(grid, 14, "Vencimiento", vencimiento, errVencimiento);
        addRow(grid, 16, "CVV", cvv, errCvv);
        return grid;
    }

    private static void addRow(GridPane grid, int row, String caption,
                               javafx.scene.Node field, Label error) {
        GridPane.setMargin(field, new Insets(2, 0, 2, 0));
        grid.add(new Label(caption), 0, row);
        grid.add(field, 1, row);
        if (error != null) {
            grid.add(error, 1, row + 1);
        }
    }

    private static void showInline(Label label, String error) {
        label.setText(error == null ? "" : error);
        label.setVisible(error != null && !error.isBlank());
    }

    private static Label errorLabel() {
        var label = new Label("");
        label.setStyle("-fx-text-fill: red; -fx-font-size: 10px;");
        label.setVisible(false);
        return label;
    }

    private static void errorDialog(Window owner, String title, String reference) {
        var alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText("No se pudo completar el pago (referencia "
                + (reference == null ? "-" : reference) + "). Intenta de nuevo.");
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }
}
