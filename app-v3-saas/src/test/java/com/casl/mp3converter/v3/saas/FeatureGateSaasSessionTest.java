package com.casl.mp3converter.v3.saas;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 9.2 — FeatureGate por suscripción contra un BACKEND STUB (HttpServer
 * del JDK, cero Spring): ciclo ACTIVE/INACTIVE/EXPIRED + tolerancia offline
 * con la gracia guardada. El almacén de sesión se redirige a un TempDir.
 */
@DisplayName("Fase 9.2: heartbeat + gracia offline de 7 días (backend stub JDK)")
class FeatureGateSaasSessionTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicReference<String> heartbeatResponse = new AtomicReference<>("200");
    private final AtomicReference<Integer> heartbeatCode = new AtomicReference<>(200);

    @BeforeAll
    static void quietJvm() {
        System.clearProperty("jpackage.app-path");
    }

    @BeforeEach
    void startStub() throws IOException {
        // sesión local aislada para ESTE test:
        System.setProperty("mp3converter.saas.session",
                tempDir.resolve("mp3converter.saas").toString());

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/license/heartbeat", exchange -> {
            byte[] body = heartbeatResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(heartbeatCode.get(), body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        FeatureGate.setApiClient(new SaasApiClient("http://localhost:" + port));
        FeatureGate.resetForTests();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
        FeatureGate.resetForTests();
        System.clearProperty("mp3converter.saas.session");
    }

    @Test
    @DisplayName("sin sesión -> FREE; token + ACTIVE -> PRO y gracia persistida")
    void active() {
        FeatureGate.refreshAtStartup();
        assertFalse(FeatureGate.isPro(), "sin sesión local: FREE");

        Instant grace = Instant.now().plus(FeatureGate.GRACE);
        heartbeatCode.set(200);
        heartbeatResponse.set("{\"status\":\"ACTIVE\",\"graceUntil\":\"" + grace + "\"}");

        FeatureGate.activateWithToken("TOKEN-DE-PRUEBA", grace, "ana@test.com");
        assertTrue(FeatureGate.isPro(), "activación en caliente con token del backend");

        // la sesión quedó en disco con el token y la gracia (precisión de ms:
        // el store persiste epoch-millis, la ventana de gracia es de días):
        var session = SaasSessionStore.load().orElseThrow();
        assertAll("sesión persistida",
                () -> assertEquals("TOKEN-DE-PRUEBA", session.token()),
                () -> assertEquals(grace.truncatedTo(ChronoUnit.MILLIS),
                        session.graceUntil().truncatedTo(ChronoUnit.MILLIS)),
                () -> assertEquals("ana@test.com", session.email())
        );

        // arranque posterior (simula reiniciar el exe) -> heartbeat -> PRO:
        FeatureGate.setProEnabled(false);
        FeatureGate.refreshAtStartup();
        assertTrue(FeatureGate.isPro(), "reinicio: heartbeat ACTIVE -> PRO sin re-login");
    }

    @Test
    @DisplayName("heartbeat INACTIVE (cancelada) -> FREE y sesión borrada")
    void inactive() {
        Instant grace = Instant.now().plus(FeatureGate.GRACE);
        FeatureGate.activateWithToken("TOKEN-CANCELADA", grace, "ana@test.com");
        assertTrue(FeatureGate.isPro());

        heartbeatCode.set(401);
        heartbeatResponse.set("{\"status\":\"INACTIVE\",\"error\":\"suscripción no activa\"}");
        FeatureGate.refreshAtStartup();
        assertAll(
                () -> assertFalse(FeatureGate.isPro(), "cancelada -> FREE"),
                () -> assertTrue(FeatureGate.lastReason().contains("inactiva")),
                () -> assertTrue(SaasSessionStore.load().isEmpty(), "sesión borrada")
        );
    }

    @Test
    @DisplayName("heartbeat EXPIRED (>7 días) -> FREE y sesión borrada")
    void expired() {
        Instant grace = Instant.now().plus(FeatureGate.GRACE);
        FeatureGate.activateWithToken("TOKEN-VENCIDO", grace, "ana@test.com");

        heartbeatCode.set(401);
        heartbeatResponse.set("{\"status\":\"EXPIRED\",\"error\":\"token vencido\"}");
        FeatureGate.refreshAtStartup();
        assertAll(
                () -> assertFalse(FeatureGate.isPro()),
                () -> assertTrue(FeatureGate.lastReason().contains("vencido")),
                () -> assertTrue(SaasSessionStore.load().isEmpty())
        );
    }

    @Test
    @DisplayName("SIN RED: gracia vigente sostiene PRO; gracia vencida -> FREE")
    void offlineGrace() throws IOException {
        Instant vigente = Instant.now().plus(FeatureGate.GRACE);
        Instant vencida = Instant.now().minusSeconds(60);

        // apagar el stub -> sin red de verdad:
        server.stop(0);

        FeatureGate.activateWithToken("TOKEN-OFFLINE", vigente, "ana@test.com");
        FeatureGate.setProEnabled(false);
        FeatureGate.refreshAtStartup();
        assertTrue(FeatureGate.isPro(), "graceUntil > now sostiene PRO sin red");

        // gracia vencida en el archivo -> FREE incluso sin red:
        FeatureGate.activateWithToken("TOKEN-OFFLINE", vencida, "ana@test.com");
        FeatureGate.setProEnabled(false);
        FeatureGate.refreshAtStartup();
        assertFalse(FeatureGate.isPro(), "gracia vencida + sin red -> FREE");
        assertTrue(FeatureGate.lastReason().contains("gracia vencida"));
    }
}
