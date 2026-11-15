package com.casl.mp3converter.saas;

import com.casl.mp3converter.saas.license.LicenseTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 8.4 — Ciclo de vida del token de licencia: issue exige suscripción
 * ACTIVE; el token NUNCA vive en claro en la BD (sólo su HMAC, la regla de
 * v2 aplicada al SaaS); heartbeat con token válido renueva la gracia de
 * 7 días; cancelar la suscripción → INACTIVE; token desconocido → EXPIRED.
 */
@DisplayName("Fase 8.4: license issue + heartbeat (gracia offline 7 días)")
class LicenseFlowTest extends SaasFlowSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("issue SIN suscripción -> 403; con suscripción -> token + graceUntil")
    void issue() throws Exception {
        String bearer = registerAndLogin();

        // 1. sin suscripción -> 403 (nunca 500):
        mvc.perform(post("/api/license/issue")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden());

        // 2. suscripción ACTIVE -> token emitido:
        subscribeActive(bearer, "pro-mensual");
        MvcResult issued = mvc.perform(post("/api/license/issue")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.graceUntil").isString())
                .andReturn();
        String token = issued.getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // 3. privacidad: la BD guarda el HASH (64 hex), jamás el token en claro:
        var rows = jdbc.queryForList(
                "SELECT token_hash FROM saas.license_tokens", String.class);
        assertAll(
                () -> assertFalse(rows.isEmpty(), "hay fila de token"),
                () -> assertTrue(rows.stream().allMatch(h ->
                                h.length() == 64 && !h.contains(token)),
                        "sólo hashes, sin el token plano")
        );
    }

    @Test
    @DisplayName("heartbeat: ACTIVE renueva gracia -> cancel de suscripción -> INACTIVE -> token falso -> EXPIRED")
    void heartbeat() throws Exception {
        String bearer = userConSuscripcionActiva();
        MvcResult issued = mvc.perform(post("/api/license/issue")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isCreated()).andReturn();
        String token = issued.getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // 1. heartbeat con el token correcto -> ACTIVE + gracia renovada:
        mvc.perform(post("/api/license/heartbeat").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.graceUntil").isString());

        // 2. token desconocido -> EXPIRED (401, nunca 500):
        mvc.perform(post("/api/license/heartbeat").contentType(APPLICATION_JSON)
                        .content("{\"token\":\"falso-no-existe\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        // 3. cancelar la suscripción en caliente (endpoint ya probado en 8.3)
        //    -> el token queda sin sustento:
        String body = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/subscriptions")
                                .header("Authorization", "Bearer " + bearer))
                .andReturn().getResponse().getContentAsString();
        String sub = body.replaceAll(".*\"id\":(\\d+).*", "$1");
        mvc.perform(post("/api/subscriptions").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"action\":\"cancel\",\"subscriptionId\":%s}".formatted(sub)))
                .andExpect(status().isOk());

        // 4. heartbeat tras cancelar -> INACTIVE (la app volvería a FREE):
        mvc.perform(post("/api/license/heartbeat").contentType(APPLICATION_JSON)
                .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }
}
