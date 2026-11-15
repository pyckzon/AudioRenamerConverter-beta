package com.casl.mp3converter.v3.ui;

import com.casl.mp3converter.v3.saas.FeatureGate;
import com.casl.mp3converter.v3.saas.SaasApiClient;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FASE 9.3 — Diálogo de SUSCRIPCIÓN (sustituye al pago+activación de v2):
 * <ol>
 *   <li>Crear cuenta / iniciar sesión contra el backend (email + password).</li>
 *   <li>Checkout: el backend crea la sesión del gateway y la app ABRE EL
 *       NAVEGADOR — la tarjeta NUNCA pasa por la app (regla del proyecto).</li>
 *   <li>"Ya completé el pago" → issue del token de licencia →
 *       {@link FeatureGate#activateWithToken} → PRO EN CALIENTE.</li>
 * </ol>
 * <p>
 * Lecciones de la Fase 5 aplicadas (probadas con sondas): los botones
 * CONSUMEN su ActionEvent (el diálogo no se cierra hasta terminar el flujo),
 * el estado va en un slot FIJO con altura mínima (sin translapes), X funcional
 * vía un ButtonType CANCEL_CLOSE oculto, y NINGÚN modal anidado dentro de un
 * resultConverter — aquí ni siquiera se usa resultConverter.
 */
public final class SubscriptionDialogV3 {

    private static final Logger LOGGER = Logger.getLogger(SubscriptionDialogV3.class.getName());

    private SubscriptionDialogV3() {
    }

    /** @param onActivated corre tras el hot flip a PRO (refresca la UI principal). */
    public static void show(Window owner, Runnable onActivated) {
        var dialog = new Dialog<Void>();
        dialog.setTitle("Suscribirse a la versión Pro");
        dialog.setHeaderText("Crea tu cuenta y suscríbete: el pago ocurre en la web "
                + "de la pasarela, tu tarjeta nunca pasa por esta app.");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setResizable(true);

        var email = new TextField();
        email.setPromptText("tu@correo.com");
        var password = new PasswordField();
        password.setPromptText("mínimo 8 caracteres");
        var nombre = new TextField();
        nombre.setPromptText("solo letras, mínimo 2");
        var apellido = new TextField();
        apellido.setPromptText("solo letras, mínimo 2");
        var plan = new ComboBox<String>();
        plan.getItems().addAll(
                "pro-mensual · 99.00 MXN/mes",
                "pro-anual · 999.00 MXN/año");
        plan.getSelectionModel().selectFirst();

        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(new Label("Correo:"), 0, 0);
        grid.add(email, 1, 0);
        grid.add(new Label("Contraseña:"), 0, 1);
        grid.add(password, 1, 1);
        grid.add(new Label("Nombre:"), 0, 2);
        grid.add(nombre, 1, 2);
        grid.add(new Label("Apellido:"), 0, 3);
        grid.add(apellido, 1, 3);
        grid.add(new Label("Plan:"), 0, 4);
        grid.add(plan, 1, 4);
        dialog.getDialogPane().setContent(grid);

        // Botones que CONSUMEN su evento (el diálogo permanece abierto):
        var crearCuenta = new ButtonType("Crear cuenta y suscribirme", ButtonBar.ButtonData.LEFT);
        var iniciarSesion = new ButtonType("Ya tengo cuenta", ButtonBar.ButtonData.LEFT);
        var yaPague = new ButtonType("Ya completé el pago", ButtonBar.ButtonData.LEFT);
        // X de la barra de título funcional (truco del CANCEL_CLOSE oculto):
        var salirOculto = new ButtonType("Salir", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(crearCuenta, iniciarSesion, yaPague, salirOculto);

        // Slot FIJO de estado (lección d798e38: sin translapes nunca):
        var estado = new Label(" ");
        estado.setWrapText(true);
        estado.setMinHeight(44);
        estado.setMinWidth(440);
        estado.setMaxWidth(440);
        estado.setStyle("-fx-font-size: 11px; -fx-text-fill: #444444;");
        dialog.getDialogPane().setExpandableContent(null);
        dialog.getDialogPane().setContent(new javafx.scene.layout.VBox(8, grid, estado));
        dialog.getDialogPane().setMinWidth(500);

        var jwt = new AtomicReference<String>(null);
        var correoEnUso = new AtomicReference<String>(null);

        dialog.showingProperty().addListener((obs, was, is) -> {
            if (is) {
                var pane = dialog.getDialogPane();
                Button botonYaPague = (Button) pane.lookupButton(yaPague);
                if (botonYaPague != null) {
                    botonYaPague.setVisible(false);
                    botonYaPague.setManaged(false);
                }
                Button botonSalir = (Button) pane.lookupButton(salirOculto);
                if (botonSalir != null) {
                    botonSalir.setVisible(false);
                    botonSalir.setManaged(false);
                }
            }
        });

        // --- 1) crear cuenta + login + checkout --------------------------------
        Button botonCrear = (Button) dialog.getDialogPane().lookupButton(crearCuenta);
        botonCrear.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); // el diálogo permanece abierto (lección 19f3b9d)
            try {
                flujoCuenta(dialog, estado, email, password, nombre, apellido, plan,
                        jwt, correoEnUso, true, botonCrear.getParent().getScene());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "flujo de cuenta falló", e);
                estado.setText("Error: " + e.getMessage());
            }
        });

        // --- 2) login directo (cuenta existente) + checkout ---------------------
        Button botonLogin = (Button) dialog.getDialogPane().lookupButton(iniciarSesion);
        botonLogin.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            try {
                flujoCuenta(dialog, estado, email, password, nombre, apellido, plan,
                        jwt, correoEnUso, false, botonLogin.getParent().getScene());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "flujo de sesión falló", e);
                estado.setText("Error: " + e.getMessage());
            }
        });

        // --- 3) "Ya completé el pago" → issue → PRO en caliente -----------------
        Button botonYaPagueRef = (Button) dialog.getDialogPane().lookupButton(yaPague);
        botonYaPagueRef.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            try {
                estado.setText("Verificando el pago y emitiendo tu licencia...");
                var issued = dialog.getDialogPane() == null ? null
                        : cliente().issue(jwt.get());
                FeatureGate.activateWithToken(issued.token(), issued.graceUntil(),
                        correoEnUso.get());
                estado.setText("✔ Suscripción activa. Pasando a PRO...");
                dialog.close(); // cierre manual SEGURO: ningún modal anidado aquí
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "issue tras pago falló", e);
                estado.setText("Aún no detectamos tu pago: espera unos segundos y "
                        + "vuelve a pulsar «Ya completé el pago». (" + e.getMessage() + ")");
            }
        });

        // Reacción en caliente DESPUÉS del cierre (lección 2674ddd):
        dialog.setOnHiding(event -> {
            if (Boolean.TRUE.equals(jwt.get() != null)) {
                // noop: el hot flip ya ocurrió en el botón
            }
            if (FeatureGate.isPro() && onActivated != null) {
                javafx.application.Platform.runLater(onActivated);
            }
        });

        dialog.showAndWait();
    }

    /** registra (opcional) + login + checkout + abrir navegador. */
    private static void flujoCuenta(Dialog<Void> dialog, Label estado,
                                    TextField email, PasswordField password,
                                    TextField nombre, TextField apellido,
                                    ComboBox<String> plan,
                                    AtomicReference<String> jwt,
                                    AtomicReference<String> correoEnUso,
                                    boolean registrar,
                                    javafx.scene.Scene scene) throws Exception {
        String correo = email.getText() == null ? "" : email.getText().trim().toLowerCase();
        String pass = password.getText() == null ? "" : password.getText();
        String nom = nombre.getText() == null ? "" : nombre.getText().trim();
        String ape = apellido.getText() == null ? "" : apellido.getText().trim();

        // Validación en línea (reglas del backend, NADA silencioso):
        if (!correo.matches("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$")) {
            estado.setText("Correo inválido. Revisa el formato (ej. tu@correo.com).");
            return;
        }
        if (pass.length() < 8) {
            estado.setText("La contraseña debe tener al menos 8 caracteres.");
            return;
        }
        if (registrar && (nom.length() < 2 || ape.length() < 2
                || !nom.matches("[\\p{L} ]+") || !ape.matches("[\\p{L} ]+"))) {
            estado.setText("Nombre y apellido: solo letras, mínimo 2 caracteres.");
            return;
        }

        var client = cliente();
        if (registrar) {
            try {
                estado.setText("Creando tu cuenta...");
                client.register(correo, pass, nom, ape);
            } catch (Exception e) {
                estado.setText("No se pudo crear la cuenta: " + e.getMessage()
                        + (e.getMessage() != null && e.getMessage().contains("ya está registrado")
                        ? " → usa «Ya tengo cuenta»." : ""));
                return;
            }
        }
        try {
            estado.setText("Iniciando sesión...");
            jwt.set(client.login(correo, pass));
        } catch (Exception e) {
            estado.setText("No se pudo iniciar sesión: " + e.getMessage());
            return;
        }
        correoEnUso.set(correo);

        String planCode = plan.getValue().startsWith("pro-anual") ? "pro-anual" : "pro-mensual";
        estado.setText("Creando la sesión de pago...");
        var checkout = client.checkout(jwt.get(), planCode);
        abrirNavegador(checkout.checkoutUrl(), scene);
        estado.setText("✔ Se abrió tu navegador con el checkout (" + checkout.checkoutUrl() + ")\n"
                + "Cuando termines el pago, vuelve aquí y pulsa «Ya completé el pago».");

        Button botonYaPague = (Button) dialog.getDialogPane().lookupButton(
                dialog.getDialogPane().getButtonTypes().stream()
                        .filter(bt -> bt.getText() != null && bt.getText().contains("Ya completé"))
                        .findFirst().orElseThrow());
        botonYaPague.setVisible(true);
        botonYaPague.setManaged(true);
    }

    /** Abre el checkout web (la tarjeta vive en el navegador, NO en la app). */
    private static void abrirNavegador(String url, javafx.scene.Scene scene) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            throw new IllegalStateException("Desktop.BROWSE no soportado");
        } catch (Exception e) {
            LOGGER.warning("No se pudo abrir el navegador automáticamente: " + e);
            // Copia la URL al portapapeles como respaldo (nunca silencioso):
            try {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(url);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                if (scene != null) {
                    javafx.scene.control.Alert alerta = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.INFORMATION,
                            "Copia la URL del checkout:\n" + url);
                    alerta.initOwner(scene.getWindow());
                    alerta.setHeaderText("Abre este enlace en tu navegador");
                    alerta.showAndWait();
                }
            } catch (Exception copia) {
                LOGGER.log(Level.SEVERE, "Ni navegador ni portapapeles disponibles", copia);
            }
        }
    }

    /** cliente SaaS por el punto de inyección del FeatureGate (mismo endpoint). */
    private static SaasApiClient cliente() {
        return FeatureGate.apiClient();
    }
}
