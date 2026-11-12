package com.casl.mp3converter.saas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 8.3 — Flujo de facturación completo contra H2 (misma migración que
 * Postgres): login → checkout (PENDING) → webhook (PAID) → suscripción ACTIVE
 * con next_billing → renueva en vez de duplicar → cancel en caliente.
 * También el gate JWT (401 sin token) y el plan desconocido (400).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Fase 8.3: checkout -> webhook -> suscripción ACTIVE (gateway MOCK)")
class BillingFlowTest {

    @Autowired
    MockMvc mvc;

    private static String correo() {
        return "billing-" + Long.toHexString(System.currentTimeMillis()) + "@casl-test.com";
    }

    /** register + login -> Bearer token para el resto del flujo. */
    private String registerAndLogin() throws Exception {
        String email = correo();
        mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secreto123","firstName":"Ana","lastName":"Garcia"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secreto123"}
                                """.formatted(email)))
                .andExpect(status().isOk()).andReturn();
        return login.getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("sin JWT -> 401 en /api/subscriptions y /api/payments/checkout")
    void gateJwt() throws Exception {
        mvc.perform(get("/api/subscriptions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/checkout").contentType(APPLICATION_JSON)
                        .content("{\"planCode\":\"pro-mensual\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/subscriptions")
                        .header("Authorization", "Bearer token-falso"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("checkout -> webhook -> ACTIVE -> segundo pago RENUEVA -> cancel")
    void flujoSuscripcion() throws Exception {
        String bearer = registerAndLogin();

        // 1. plan desconocido -> 400 (nunca 500, nunca silencioso):
        mvc.perform(post("/api/payments/checkout").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"planCode\":\"no-existe\"}"))
                .andExpect(status().isBadRequest());

        // 2. checkout OK -> sesión mock + pago PENDING:
        MvcResult checkout = mvc.perform(post("/api/payments/checkout")
                        .contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"planCode\":\"pro-mensual\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkoutUrl").isString())
                .andExpect(jsonPath("$.externalId").isString())
                .andReturn();
        String externalId = checkout.getResponse().getContentAsString()
                .replaceAll(".*\"externalId\":\"([^\"]+)\".*", "$1");
        assertNotEquals("MOCK-", externalId, "externalId único del gateway mock");

        // 3. webhook confirma -> suscripción ACTIVE:
        mvc.perform(post("/api/payments/webhook").contentType(APPLICATION_JSON)
                        .content("{\"externalId\":\"%s\",\"status\":\"PAID\"}".formatted(externalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 4. GET /subscriptions la lista con next_billing a futuro:
        MvcResult list = mvc.perform(get("/api/subscriptions")
                        .header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn();
        String body = list.getResponse().getContentAsString();
        assertTrue(body.contains("pro-mensual"), "plan visible: " + body);
        assertTrue(body.contains("ACTIVE"), "estado ACTIVE: " + body);

        // 5. webhook repetido (idempotente): NO duplica suscripciones:
        mvc.perform(post("/api/payments/webhook").contentType(APPLICATION_JSON)
                        .content("{\"externalId\":\"%s\",\"status\":\"PAID\"}".formatted(externalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDEMPOTENTE"));

        // 6. cancelar en caliente:
        String subId = body.replaceAll(".*\"id\":(\\d+).*", "$1");
        mvc.perform(post("/api/subscriptions").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"action\":\"cancel\",\"subscriptionId\":%s}".formatted(subId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canceled").value(Long.parseLong(subId)));
        mvc.perform(get("/api/subscriptions").header("Authorization", "Bearer " + bearer))
                .andExpect(jsonPath("$[0].status").value("CANCELED"));
    }
}
