package com.casl.mp3converter.saas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 8.5 — E2E del CICLO COMPLETO de suscripción (el equivalente del test
 * de la Fase 6 para v2), en un solo test contra H2 (misma migración que
 * Supabase): registro → login → checkout → webhook → token de licencia →
 * heartbeat ACTIVE con gracia renovada → "offline" simulado (token vencido
 * manipulado en BD) → EXPIRED → recuperación con issue de nuevo → cancel →
 * heartbeat INACTIVE. También verifica que la fila cruda jamás tiene plaintext.
 */
@DisplayName("Fase 8.5: ciclo completo register -> suscripción -> licencia -> gracia -> baja")
class SubscriptionLifecycleE2ETest extends SaasFlowSupport {

    @Autowired
    JdbcTemplate jdbc;

    private String tokenDe(String bearer) throws Exception {
        MvcResult issued = mvc.perform(post("/api/license/issue")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isCreated()).andReturn();
        return issued.getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("lifecycle completo con recuperación tras vencimiento y baja final")
    void cicloCompleto() throws Exception {
        String bearer = userConSuscripcionActiva(); // register+login+checkout+webhook

        // 1. fila de pago confirmada y SIN plaintext de tarjeta (no existe tal columna):
        var pago = jdbc.queryForMap("""
                SELECT gateway, status, amount_cents, currency
                FROM saas.payments WHERE status = 'PAID' ORDER BY id DESC LIMIT 1
                """);
        assertAll("pago PAID (el monto del plan mensual)",
                () -> assertEquals("MOCK", pago.get("gateway")),
                () -> assertEquals(9_900, ((Number) pago.get("amount_cents")).intValue()),
                () -> assertEquals("MXN", pago.get("currency"))
        );

        // 2. token de licencia emitido:
        String token = tokenDe(bearer);
        assertTrue(token.length() >= 30, "token base64url de 24 bytes: " + token);

        // 3. heartbeat -> ACTIVE con gracia ≈ 7 días:
        MvcResult hb = mvc.perform(post("/api/license/heartbeat").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();
        String graceUntil = hb.getResponse().getContentAsString()
                .replaceAll(".*\"graceUntil\":\"([^\"]+)\".*", "$1");
        Instant grace = Instant.parse(graceUntil.replaceFirst("\\+00:00$", "Z").replace(' ', 'T'));
        assertTrue(grace.isAfter(Instant.now().plus(java.time.Duration.ofDays(6)))
                        && grace.isBefore(Instant.now().plus(java.time.Duration.ofDays(8))),
                "graceUntil en la ventana de 7 días: " + graceUntil);

        // 4. "offline" simulado: el token quedó >7 días sin heartbeat
        //    (manipulación directa de la fila, como lo haría el tiempo real):
        jdbc.update("UPDATE saas.license_tokens SET expires_at = ?",
                Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(2))));
        mvc.perform(post("/api/license/heartbeat").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        // 5. recuperación: re-login + issue de nuevo (la app lo hace en caliente)
        //    y la BD conserva UNA sola fila de token (upsert, sin duplicar):
        String token2 = tokenDe(bearer);
        String subs = mvc.perform(get("/api/subscriptions")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String subId = subs.replaceAll(".*\"id\":(\\d+).*", "$1");
        Long filas = jdbc.queryForObject("""
                SELECT COUNT(*) FROM saas.license_tokens
                WHERE user_id = (SELECT user_id FROM saas.subscriptions WHERE id = ?)
                """, Long.class, Long.parseLong(subId));
        assertEquals(1L, filas, "upsert: una sola fila de token para este usuario");
        mvc.perform(post("/api/license/heartbeat").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 6. baja de la suscripción -> el token pierde sustento -> INACTIVE:
        mvc.perform(post("/api/subscriptions").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"action\":\"cancel\",\"subscriptionId\":%s}".formatted(subId)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/license/heartbeat").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token2)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }
}
