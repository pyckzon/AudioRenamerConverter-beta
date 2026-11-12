package com.casl.mp3converter.saas;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 8.4 — Apoyo para tests de flujo: register + login (JWT) y
 * checkout + webhook (suscripción ACTIVE), reutilizable por sub-fase.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class SaasFlowSupport {

    @Autowired
    protected MockMvc mvc;

    protected String registerAndLogin() throws Exception {
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

    /** checkout + webhook PAID con el plan dado (mock) -> suscripción ACTIVE. */
    protected void subscribeActive(String bearer, String planCode) throws Exception {
        MvcResult checkout = mvc.perform(post("/api/payments/checkout")
                        .contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + bearer)
                        .content("{\"planCode\":\"%s\"}".formatted(planCode)))
                .andExpect(status().isCreated()).andReturn();
        String externalId = checkout.getResponse().getContentAsString()
                .replaceAll(".*\"externalId\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/api/payments/webhook").contentType(APPLICATION_JSON)
                        .content("{\"externalId\":\"%s\",\"status\":\"PAID\"}".formatted(externalId)))
                .andExpect(status().isOk());
    }

    /** Deja un usuario con suscripción mensual activa y devuelve su Bearer token. */
    protected String userConSuscripcionActiva() throws Exception {
        String bearer = registerAndLogin();
        subscribeActive(bearer, "pro-mensual");
        return bearer;
    }

    private static String correo() {
        return "flow-" + Long.toHexString(System.nanoTime()) + "@casl-test.com";
    }
}
