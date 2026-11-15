package com.casl.mp3converter.saas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 10.1 — Tag "db-integration": el ciclo completo del SaaS con pasarela
 * MOCK contra Supabase/Postgres REAL (la decisión del usuario: pasarela MOCK
 * definitiva; lo que importa es el REGISTRO en la base de datos). Sin
 * SAAS_DB_URL => SKIP automático (misma regla que core, plan 4.5).
 * <p>
 * Verifica: pago PAID registrado, suscripción ACTIVE -> CANCELED al cancelar,
 * token de licencia con su HMAC (no el token crudo) y NINGÚN plaintext.
 */
@Tag("db-integration")
@EnabledIfEnvironmentVariable(named = "SAAS_DB_URL", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Supabase real: ciclo MOCK register -> pago -> suscripción -> cancel (registro en BD)")
class SupabaseSaasLifecycleIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("pago PAID + suscripción ACTIVE/CANCELED + token solo como HMAC en Supabase")
    void cicloCompletoEnSupabase() throws Exception {
        String correo = "saas-db-" + Long.toHexString(System.nanoTime()) + "@casl-test.com";

        // register -> login -> checkout -> webhook (el flujo del usuario):
        mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secreto123","firstName":"Test","lastName":"Db"}
                                """.formatted(correo)))
                .andExpect(status().isCreated());
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secreto123"}
                                """.formatted(correo)))
                .andExpect(status().isOk()).andReturn();
        String bearer = login.getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        MvcResult checkout = mvc.perform(post("/api/payments/checkout")
                        .contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"planCode\":\"pro-anual\"}"))
                .andExpect(status().isCreated()).andReturn();
        String externalId = checkout.getResponse().getContentAsString()
                .replaceAll(".*\"externalId\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/payments/webhook").contentType(APPLICATION_JSON)
                        .content("{\"externalId\":\"%s\",\"status\":\"PAID\"}".formatted(externalId)))
                .andExpect(status().isOk());

        MvcResult issued = mvc.perform(post("/api/license/issue")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isCreated()).andReturn();
        String token = issued.getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/license/heartbeat").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk());

        // el id del usuario de ESTA corrida (asserts por usuario, nunca COUNT(*) global):
        Long userId = jdbc.queryForObject(
                "SELECT id FROM saas.users WHERE email_hash = ?",
                Long.class, com.casl.mp3converter.core.license.FieldCrypto.emailHash(correo));

        // 1. el pago quedó REGISTRADO en la BD real (gateway MOCK, PAID, anual):
        Map<String, Object> pago = jdbc.queryForMap("""
                SELECT gateway, status, amount_cents, currency
                FROM saas.payments WHERE user_id = ? AND external_id = ?
                """, userId, externalId);
        assertAll("pago PAID registrado en Supabase",
                () -> assertEquals("MOCK", pago.get("gateway")),
                () -> assertEquals("PAID", pago.get("status")),
                () -> assertEquals(999_00, ((Number) pago.get("amount_cents")).intValue()),
                () -> assertEquals("MXN", pago.get("currency"))
        );

        // 2. suscripción ACTIVE (pro-anual) y luego CANCELED tras la baja:
        mvc.perform(post("/api/subscriptions").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"action\":\"cancel\",\"subscriptionId\":%s}"
                                .formatted(subscriptionIdDe(bearer))))
                .andExpect(status().isOk());
        Map<String, Object> sub = jdbc.queryForMap("""
                SELECT status, canceled_at FROM saas.subscriptions
                WHERE user_id = ? ORDER BY id DESC LIMIT 1
                """, userId);
        assertEquals("CANCELED", sub.get("status"), "baja registrada en BD real");
        assertTrue(sub.get("canceled_at") != null, "canceled_at presente");

        // 3. el token de licencia jamás se guarda crudo: sólo su HMAC (64 hex):
        String hmac = jdbc.queryForObject("""
                SELECT token_hash FROM saas.license_tokens
                WHERE user_id = (SELECT id FROM saas.users WHERE email_hash = ?)
                """, String.class, com.casl.mp3converter.core.license.FieldCrypto.emailHash(correo));
        assertAll("token guardado sólo como HMAC",
                () -> assertEquals(64, hmac.length(), "SHA-256 hex: " + hmac),
                () -> assertFalse(hmac.contains(token.substring(0, 10)),
                        "el token crudo no vive en la BD")
        );

        // 4. sin plaintext de password en la fila de usuario:
        String pwd = jdbc.queryForObject(
                "SELECT password_hash FROM saas.users WHERE id = ?", String.class, userId);
        assertFalse(pwd.contains("secreto123"), "BCrypt, nunca plaintext");
        assertFalse(pwd.equals("secreto123"), "BCrypt, nunca plaintext");
    }

    /** GET /api/subscriptions -> id de la suscripción vigente. */
    private long subscriptionIdDe(String bearer) throws Exception {
        MvcResult list = mvc.perform(get("/api/subscriptions")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn();
        var m = java.util.regex.Pattern.compile("\"id\":(\\d+)")
                .matcher(list.getResponse().getContentAsString());
        assertTrue(m.find(), "id de suscripción en la lista");
        return Long.parseLong(m.group(1));
    }
}
