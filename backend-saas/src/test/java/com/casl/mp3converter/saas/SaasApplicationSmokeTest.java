package com.casl.mp3converter.saas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 8.1 — Humo del backend SaaS: el contexto arranca, la migración
 * idempotente crea el schema saas.* (H2 por defecto) y el health responde.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Fase 8.1: base Spring Boot + esquema saas.* + health")
class SaasApplicationSmokeTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("health responde ok")
    void health() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    @DisplayName("la migración idempotente creó las 5 tablas del schema saas")
    void esquemaCreado() {
        // Existencia de tablas (no conteo de filas: la BD en memoria con nombre
        // se comparte entre contextos de test y puede contener datos de otros).
        var names = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = 'SAAS'", String.class);
        assertAll(
                () -> assertTrue(names.containsAll(java.util.List.of(
                        "USERS", "PLANS", "SUBSCRIPTIONS", "PAYMENTS", "LICENSE_TOKENS")),
                        "5 tablas del esquema saas, en minúscula H2 las ve en MAYÚSCULA: " + names)
        );
    }
}
