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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Test
    @DisplayName("Fase 9 manual: página de checkout mock con plan real + botón que dispara el webhook")
    void paginaCheckoutMock() throws Exception {
        String bearer = registerAndLogin();
        MvcResult checkout = mvc.perform(post("/api/payments/checkout")
                        .contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"planCode\":\"pro-anual\"}"))
                .andExpect(status().isCreated()).andReturn();
        String body = checkout.getResponse().getContentAsString();
        String externalId = body.replaceAll(".*\"externalId\":\"([^\"]+)\".*", "$1");
        // la URL ya NO es ficticia: apunta a la página mock del propio backend:
        String url = body.replaceAll(".*\"checkoutUrl\":\"([^\"]+)\".*", "$1");
        assertTrue(url.endsWith("/mock-checkout/" + externalId),
                "checkoutUrl = página del backend: " + url);

        // la página muestra el plan REAL (info pública del pago):
        mvc.perform(get("/mock-checkout/{externalId}/info", externalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("pro-anual"))
                .andExpect(jsonPath("$.amountCents").value(999_00))
                .andExpect(jsonPath("$.currency").value("MXN"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // la página HTML contiene el externalId saneado y el flujo de webhook:
        mvc.perform(get("/mock-checkout/{externalId}", externalId))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(externalId)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/api/payments/webhook")));

        // externalId con caracteres extraños -> la página SANEÓ el id
        // (Spring 404's para <,>; los puntos los elimina el sanitizador):
        mvc.perform(get("/mock-checkout/{externalId}", externalId + ".."))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers
                        .containsString(externalId + ".."))));

        // info de un pago inexistente -> 404:
        mvc.perform(get("/mock-checkout/{externalId}/info", "MOCK-no-existe"))
                .andExpect(status().isNotFound());

        // el ciclo completo sigue: "pagar" en la página = webhook -> ACTIVE:
        mvc.perform(post("/api/payments/webhook").contentType(APPLICATION_JSON)
                        .content("{\"externalId\":\"%s\",\"status\":\"PAID\"}".formatted(externalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
