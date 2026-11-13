package com.casl.mp3converter.v3.saas;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 9.4 — E2E de la app v3 contra el BACKEND REAL (Fase 8) corriendo como
 * proceso JVM hijo con el classpath de runtime de {@code :backend-saas} (H2 en
 * memoria, la misma migración que Supabase). El ciclo corre por HTTP DE VERDAD
 * con el SaasApiClient de la app: register → login → checkout → webhook PAID
 * (lo que dispara la pasarela) → issue → PRO en caliente → heartbeat → cancel
 * → INACTIVE → FREE. Es el equivalente en v3 del E2E de la Fase 6.
 * <p>
 * El backend va en un proceso hijo (y no en este classpath) porque los tests
 * se compilan parchados al módulo FX (plugin de modularidad) y acoplar Spring
 * ahí ensuciaría el module-info. La app en producción habla HTTP igual.
 */
@DisplayName("Fase 9.4: E2E app v3 contra backend REAL en proceso hijo (suscripción, heartbeat, baja)")
class AppV3SaasLifecycleE2ETest {

    private static Process backend;
    private static int port;
    private static Path logDelBackend;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void startBackend() throws Exception {
        String classpath = System.getProperty("saas.e2e.backend.classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException(
                    "falta saas.e2e.backend.classpath (lo inyecta :app-v3-saas:test)");
        }
        // puerto libre en ESTA máquina para el hijo:
        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        logDelBackend = Files.createTempFile("saas-backend-e2e", ".log");

        String java = Path.of(System.getProperty("java.home"),
                "bin", "java.exe").toString();
        var command = new ArrayList<>(List.of(java, "-cp", classpath,
                "com.casl.mp3converter.saas.SaasApplication",
                "--server.port=" + port));
        var pb = new ProcessBuilder(command);
        pb.environment().put("SAAS_JWT_SECRET",
                "e2e-hijo-secret-0123456789abcdef0123456789abcdef");
        pb.redirectOutput(logDelBackend.toFile());
        pb.redirectErrorStream(true);
        backend = pb.start();
        esperarServidor();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.destroy();
        }
    }

    @AfterEach
    void resetApp() {
        FeatureGate.resetForTests();
        System.clearProperty("mp3converter.saas.session");
    }

    /** Espera hasta 60 s a que Tomcat acepte conexiones en el puerto elegido. */
    private static void esperarServidor() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (backend != null && !backend.isAlive()) {
                throw new IllegalStateException("el backend hijo murió al arrancar:\n"
                        + Files.readString(logDelBackend));
            }
            try (Socket probe = new Socket()) {
                probe.connect(new java.net.InetSocketAddress("localhost", port), 500);
                return; // Tomcat ya acepta conexiones
            } catch (IOException aunNo) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("el backend hijo no respondió en 60 s:\n"
                + Files.readString(logDelBackend));
    }

    @Test
    @DisplayName("ciclo completo: register -> checkout -> pago -> issue -> PRO -> heartbeat -> cancel -> FREE")
    void cicloCompleto() throws Exception {
        System.setProperty("mp3converter.saas.session",
                tempDir.resolve("mp3converter.saas").toString());
        FeatureGate.setApiClient(new SaasApiClient("http://localhost:" + port));

        // 1. arranque de la app SIN sesión -> FREE (como abrir el exe por primera vez):
        FeatureGate.refreshAtStartup();
        assertFalse(FeatureGate.isPro(), "sin sesión: FREE");
        assertTrue(FeatureGate.lastReason().contains("sin sesión"));

        // 2. crear cuenta + login (el flujo del SubscriptionDialogV3):
        String correo = "e2e-v3-" + Long.toHexString(System.nanoTime()) + "@casl-test.com";
        SaasApiClient api = FeatureGate.apiClient();
        api.register(correo, "secreto123", "Ana", "Garcia");
        String jwt = api.login(correo, "secreto123");

        // 3. checkout: la app abre la web de la pasarela; el gateway confirma
        //    por webhook (aquí lo simulamos con la MISMA llamada HTTP que hace
        //    el MockCheckoutGateway en producción):
        SaasApiClient.Checkout checkout = api.checkout(jwt, "pro-anual");
        assertTrue(checkout.checkoutUrl().startsWith("http"),
                "URL de checkout web: " + checkout.checkoutUrl());
        webhookPagado(checkout.externalId());

        // 4. "Ya completé el pago" -> issue -> PRO EN CALIENTE:
        SaasApiClient.Issued issued = api.issue(jwt);
        assertTrue(issued.graceUntil().isAfter(Instant.now().plus(Duration.ofDays(6))),
                "gracia ~7 días: " + issued.graceUntil());
        FeatureGate.activateWithToken(issued.token(), issued.graceUntil(), correo);
        assertAll("PRO activo",
                () -> assertTrue(FeatureGate.isPro()),
                () -> assertTrue(FeatureGate.headerTitle().contains("PRO")),
                () -> assertTrue(FeatureGate.footer().contains("suscripción activa"))
        );

        // 5. el daemon/arranque re-verifica contra el backend -> ACTIVE:
        FeatureGate.setProEnabled(false); // simula reinicio del exe
        FeatureGate.refreshAtStartup();
        assertTrue(FeatureGate.isPro(), "heartbeat ACTIVE sostiene PRO tras reinicio");
        var session = SaasSessionStore.load().orElseThrow();
        assertEquals(correo, session.email(), "la sesión recuerda la cuenta");

        // 6. baja de la suscripción (gateway/web) -> heartbeat INACTIVE -> FREE:
        cancelarSuscripcion(jwt, subscriptionIdDe(jwt));
        FeatureGate.setProEnabled(true); // fuerza el re-check real
        FeatureGate.refreshAtStartup();
        assertAll("tras la baja",
                () -> assertFalse(FeatureGate.isPro(), "INACTIVE -> FREE"),
                () -> assertTrue(FeatureGate.lastReason().contains("inactiva")),
                () -> assertTrue(SaasSessionStore.load().isEmpty(), "sesión borrada")
        );
    }

    // --- llamadas HTTP que en producción hace la pasarela / la web -----------

    private static void webhookPagado(String externalId) throws Exception {
        HttpResponse<String> res = raw("""
                {"externalId":"%s","status":"PAID"}
                """.formatted(externalId), "/api/payments/webhook");
        assertEquals(200, res.statusCode(), "webhook PAID: " + res.body());
    }

    private static void cancelarSuscripcion(String jwt, long subscriptionId) throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/subscriptions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + jwt)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"action\":\"cancel\",\"subscriptionId\":" + subscriptionId + "}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "cancel: " + res.body());
    }

    /** GET /api/subscriptions -> id de la suscripción vigente. */
    private static long subscriptionIdDe(String jwt) throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/subscriptions"))
                        .header("Authorization", "Bearer " + jwt)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "GET subscriptions: " + res.body());
        var m = Pattern.compile("\"id\":(\\d+)").matcher(res.body());
        assertTrue(m.find(), "id de suscripción en: " + res.body());
        return Long.parseLong(m.group(1));
    }

    private static HttpResponse<String> raw(String jsonBody, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
