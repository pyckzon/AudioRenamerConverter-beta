package com.casl.mp3converter.v3.saas;

import com.casl.mp3converter.v3.saas.FeatureGate;
import com.casl.mp3converter.v3.saas.SaasApiClient;
import com.casl.mp3converter.v3.saas.SaasSessionStore;
import com.casl.mp3converter.v3.ui.SubscriptionDialogV3;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * FASE 9.3 — Flujo completo del diálogo de suscripción en headless con un
 * backend STUB del JDK: crear cuenta → login → checkout → "Ya completé el
 * pago" → issue → FeatureGate PRO EN CALIENTE + sesión persistida + cierre.
 * Misma técnica de sondas que la Fase 5 (show() en el FX thread, fire() de
 * los botones, aserciones fuera del event loop).
 */
@DisplayName("Fase 9.3: flujo del diálogo de suscripción (crear cuenta -> checkout -> PRO)")
class SubscriptionDialogFlowTest {

    @TempDir
    Path tempDir;

    private static HttpServer server;

    @BeforeAll
    static void startToolkitAndStub() throws IOException {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException yaIniciado) {
            // ya estaba
        }
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/register", e -> respond(e, 201, "{\"userId\":1}"));
        server.createContext("/api/auth/login", e -> respond(e, 200,
                "{\"accessToken\":\"JWT-DE-PRUEBA\",\"userId\":1,\"expiresIn\":900}"));
        server.createContext("/api/payments/checkout", e -> respond(e, 201,
                "{\"paymentId\":1,\"gateway\":\"MOCK\","
                        + "\"checkoutUrl\":\"https://checkout.mock.local/pay/x\",\"externalId\":\"MOCK-x\"}"));
        server.createContext("/api/license/issue", e -> respond(e, 201,
                "{\"token\":\"TOKEN-LICENCIA-123\",\"graceUntil\":\"2030-01-01T00:00:00Z\"}"));
        server.createContext("/api/license/heartbeat", e -> respond(e, 200,
                "{\"status\":\"ACTIVE\",\"graceUntil\":\"2030-01-01T00:00:00Z\"}"));
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @AfterEach
    void reset() {
        FeatureGate.resetForTests();
        System.clearProperty("mp3converter.saas.session");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String json)
            throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    @DisplayName("crear cuenta -> checkout -> 'Ya completé el pago' -> PRO + sesión + cierre")
    void flujoCompleto() throws Exception {
        System.setProperty("mp3converter.saas.session",
                tempDir.resolve("mp3converter.saas").toString());
        FeatureGate.setApiClient(new SaasApiClient(
                "http://localhost:" + server.getAddress().getPort()));
        FeatureGate.resetForTests();

        AtomicError fxError = new AtomicError();
        CountDownLatch done = new CountDownLatch(1);

        // El diálogo showAndWait bloquea el FX thread: lo mostramos en un pulso
        // propio y operamos sus botones desde EL FX THREAD por pulsos (misma
        // técnica que las sondas de la Fase 5, sin showAndWait anidado):
        Platform.runLater(() -> {
            try {
                SubscriptionDialogV3.show(null, () -> { });
            } catch (Throwable t) {
                fxError.set(new IllegalStateException("show() lanzó", t));
                done.countDown();
            }
        });

        // pulso 1: esperar el pane y escribir los campos:
        AtomicReference<String> diagVentanas = new AtomicReference<>("");
        pulse(2_000, fxError, () -> {
            fxError.rethrowSiHay(); // si show() falló, la causa real aparece aquí
            DialogPane pane = paneAbiertoOrNull();
            if (pane == null) {
                var sb = new StringBuilder();
                for (var w : javafx.stage.Window.getWindows()) {
                    sb.append(w.getClass().getSimpleName()).append(" showing=")
                            .append(w.isShowing()).append(" scene=")
                            .append(w.getScene() == null ? "null" : "ok").append("; ");
                }
                diagVentanas.set(sb.toString());
            }
            DialogPane asegurado = paneAbierto("pulso 1");
            var campos = campos(asegurado);
            campos.get(0).setText("ana@test.com");
            campos.get(1).setText("secreto123");
            campos.get(2).setText("Ana");
            campos.get(3).setText("Garcia");
        });

        // pulso 2: "Crear cuenta y suscribirme" (registro + login + checkout):
        pulse(2_000, fxError, () -> {
            DialogPane pane = paneAbierto("pulso 2");
            buttonWithText(pane, "Crear cuenta").fire();
        });

        // pulso 3: verificar estado y pulsar "Ya completé el pago":
        pulse(2_000, fxError, () -> {
            DialogPane pane = paneAbierto("pulso 3");
            Button yaPague = buttonWithText(pane, "Ya completé el pago");
            assertTrue(yaPague.isVisible(),
                    "tras el checkout debe aparecer «Ya completé el pago»");
            assertTrue(yaPague.isManaged());
            yaPague.fire();
        });

        // pulso 4: hot flip + sesión + el diálogo SE CERRÓ SOLO tras el issue:
        pulse(2_000, fxError, () -> {
            assertTrue(FeatureGate.isPro(), "hot flip a PRO tras el issue");
            assertTrue(SaasSessionStore.load().isPresent(), "sesión persistida");
            assertTrue(paneAbiertoOrNull() == null,
                    "el diálogo se cerró solo tras el issue exitoso");
        });

        if (!done.await(1, TimeUnit.MILLISECONDS)) {
            // los pulsos ya validaron el flujo; sólo confirmamos sin errores FX:
        }
        if (!diagVentanas.get().isEmpty()) {
            fail("pulso 1 sin diálogo; ventanas: " + diagVentanas.get());
        }
        fxError.rethrow();
        assertAll(
                () -> assertNotNull(FeatureGate.lastReason(), "motivo auditable presente"),
                () -> assertTrue(FeatureGate.isPro())
        );
    }

    // --- helpers ------------------------------------------------------------

    private static final class AtomicError {
        private volatile Throwable error;

        void set(Throwable t) {
            if (error == null) {
                error = t; // el PRIMER error es el diagnóstico; los pulsos posteriores son eco
            }
        }

        void rethrowSiHay() {
            if (error != null) {
                throw new AssertionError("error previo en el FX thread", error);
            }
        }

        void rethrow() {
            if (error != null) {
                throw new AssertionError("fallo en el FX thread: " + error, error);
            }
        }
    }

    private static void pulse(long maxMillis, AtomicError fxError, Runnable action)
            throws InterruptedException {
        CountDownLatch pulseDone = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                fxError.set(t);
            } finally {
                pulseDone.countDown();
            }
        });
        assertTrue(pulseDone.await(maxMillis, TimeUnit.SECONDS),
                "pulso del FX thread no respondió (¿colgado?) error previo: " + (fxError.error));
    }

    private static DialogPane paneAbierto(String donde) {
        DialogPane pane = paneAbiertoOrNull();
        assertNotNull(pane, "debe haber un diálogo de suscripción abierto (" + donde + ")");
        return pane;
    }

    private static DialogPane paneAbiertoOrNull() {
        // OJO: la ventana de un Dialog NO es un Stage: JavaFX crea un
        // HeavyweightDialog$1 interno. Sólo importa el DialogPane visible.
        return javafx.stage.Window.getWindows().stream()
                .filter(w -> w.isShowing()
                        && w.getScene() != null
                        && w.getScene().getRoot() instanceof DialogPane)
                .map(w -> (DialogPane) w.getScene().getRoot())
                .findFirst().orElse(null);
    }

    private static List<TextField> campos(DialogPane pane) {
        var out = new ArrayList<TextField>();
        collect(pane, out);
        assertTrue(out.size() >= 4, "el diálogo debe tener email+password+nombre+apellido: " + out.size());
        return out;
    }

    private static void collect(javafx.scene.Parent root, List<TextField> out) {
        for (var child : root.getChildrenUnmodifiable()) {
            if (child instanceof TextField tf) {
                out.add(tf);
            }
            if (child instanceof javafx.scene.Parent p) {
                collect(p, out);
            }
        }
    }

    private static Button buttonWithText(DialogPane pane, String contains) {
        for (ButtonType bt : pane.getButtonTypes()) {
            var node = pane.lookupButton(bt);
            if (node instanceof Button b && b.getText() != null && b.getText().contains(contains)) {
                return b;
            }
        }
        throw new IllegalStateException("no hay botón que contenga: " + contains);
    }
}
