package com.casl.mp3converter.saas;

import com.casl.mp3converter.saas.auth.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FASE 8.2 — Contrato de auth contra H2 (MISMA migración saas/V1__saas.sql
 * que en Postgres): registro, dedupe por email_hash, login JWT, credenciales
 * malas y la regla de privacidad "hash, no correo" en la fila cruda.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Fase 8.2: auth (register + login JWT)")
class AuthFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtService jwt;

    private static String correo() {
        // único por ejecución (como los tests de Supabase):
        return "auth-" + Long.toHexString(System.currentTimeMillis()) + "@casl-test.com";
    }

    private String registerBody(String email) {
        return """
                {"email":"%s","password":"secreto123","firstName":"Ana","lastName":"Garcia"}
                """.formatted(email);
    }

    @Test
    @DisplayName("register 201 -> duplicado 409 -> login 200 con JWT válido -> password mala 401")
    void flujoCompleto() throws Exception {
        String email = correo();

        // 1. registro OK:
        MvcResult created = mvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON).content(registerBody(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isNumber())
                .andReturn();
        long userId = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll(".*?(\\d+).*", "$1"));

        // 2. dedupe: el MISMO correo no re-registra:
        mvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON).content(registerBody(email)))
                .andExpect(status().isConflict());

        // 3. login correcto -> JWT que verifica contra el userId registrado:
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secreto123"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andReturn();
        String token = login.getResponse().getContentAsString()
                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
        assertEquals(userId, jwt.verify(token));

        // 4. password equivocada -> 401 (nunca 500, nunca token):
        mvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"incorrecta"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        // 5. la fila cruda guarda hash (64, sin '@') y SIN correo plano:
        var row = jdbc.queryForMap(
                "SELECT email_hash, email_enc, password_hash FROM saas.users WHERE id = ?", userId);
        assertAll("privacidad en producción",
                () -> assertEquals(64, ((String) row.get("email_hash")).length()),
                () -> assertFalse(((String) row.get("email_hash")).contains("@")),
                () -> assertTrue(((byte[]) row.get("email_enc")).length > 0, "correo cifrado"),
                () -> assertTrue(((String) row.get("password_hash")).startsWith("$2"),
                        "BCrypt, nunca password en claro")
        );
    }

    @Test
    @DisplayName("validaciones: email inválido, password débil, nombre corto -> 400")
    void validaciones() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"no-es-correo","password":"secreto123","firstName":"Ana","lastName":"Garcia"}
                                """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"corta","firstName":"Ana","lastName":"Garcia"}
                                """.formatted(correo())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secreto123","firstName":"A","lastName":"Garcia"}
                                """.formatted(correo())))
                .andExpect(status().isBadRequest());
    }
}
