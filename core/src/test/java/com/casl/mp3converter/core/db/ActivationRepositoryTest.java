package com.casl.mp3converter.core.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repositorios contra una base REAL en memoria (H2): valida el SQL portable,
 * el des-duplicado y sobre todo la REGLA CRÍTICA del plan:
 * "sin subtítulos en claro en DB" — ningún campo legible en la fila cruda.
 */
@DisplayName("Repositorios con BD real en memoria (requisito 2.5.2)")
class ActivationRepositoryTest {

    private ConnectionProvider provider;
    private UserRepository users;
    private ActivationRepository activations;

    private static CustomerProfile perfil(String correo, String apellido) {
        return new CustomerProfile("Ana Laura", apellido, correo, "+52 55 1234 5678");
    }

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:repo_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        provider = () -> DriverManager.getConnection(jdbcUrl);
        users = new UserRepository(provider);
        activations = new ActivationRepository(provider);
        try (Connection connection = provider.get(); Statement st = connection.createStatement()) {
            SchemaInitializer.ensureSchema(connection);
        }
    }

    @Test
    @DisplayName("2.5.2 guardar -> recuperar -> descifrar correcto: NADA legible en la fila cruda")
    void roundtripSinPlaintext() throws Exception {
        var profile = perfil("ana.garcia@test.com", "García");

        var userId = users.upsertUser(profile, "FREE");

        try (Connection connection = provider.get()) {
            try (Statement st = connection.createStatement()) {
                // fila cruda: NINGÚN campo con el texto en claro
                try (ResultSet rs = st.executeQuery("SELECT * FROM users WHERE id=" + userId.id())) {
                    assertTrue(rs.next());
                    String row = new ArrayList<>(List.of(
                            rs.getString("email_hash"), rs.getString("tier"))).toString();
                    byte[] emailEnc = rs.getBytes("email_enc");
                    byte[] nombreEnc = rs.getBytes("first_name_enc");
                    byte[] apellidoEnc = rs.getBytes("last_name_enc");
                    byte[] phoneEnc = rs.getBytes("phone_enc");

                    assertAll(
                            () -> assertFalse(row.contains("ana.garcia@test.com")),
                            () -> assertFalse(new String(emailEnc).contains("ana.garcia@test.com")),
                            () -> assertFalse(new String(nombreEnc).contains("Ana")),
                            () -> assertFalse(new String(apellidoEnc).contains("García")),
                            () -> assertFalse(new String(phoneEnc).contains("55")),
                            () -> assertEquals(64, rs.getString("email_hash").length(), "hash y no email")
                    );
                }
            }
        }

        // recuperación con la pareja correo+apellido EXACTA:
        var decrypted = users.loadDecrypted("ana.garcia@test.com", "García");
        assertTrue(decrypted.isPresent());
        assertAll(
                () -> assertEquals("Ana Laura", decrypted.get().nombre()),
                () -> assertEquals("García", decrypted.get().apellido()),
                () -> assertEquals("ana.garcia@test.com", decrypted.get().correo()),
                () -> assertEquals("+52 55 1234 5678", decrypted.get().telefono())
        );
    }

    @Test
    @DisplayName("2.5.2 anti-duplicado: segunda activación del mismo correo NO crea otro user")
    void noDuplicaPorEmailHash() throws Exception {
        var first = users.upsertUser(perfil("reutil@test.com", "Pérez"), "FREE");
        var second = users.upsertUser(perfil("reutil@test.com", "Pérez"), "PRO");

        assertAll(
                () -> assertEquals(first.id(), second.id(), "misma cuenta => mismo id"),
                () -> assertTrue(!second.created()),
                () -> assertEquals(1, countRows("users")),
                () -> assertEquals("PRO", tierOf(first.id()),
                        "el re-upsert actualiza el tier"),
                () -> assertTrue(java.util.Arrays.equals(first.salt(), second.salt()),
                        "la salt conservada permite las claves previas")
        );
    }

    @Test
    @DisplayName("2.6 emisión: el código SIEMPRE cifrado en code_enc; hash en code_hash")
    void emisionSinPlaintext() throws Exception {
        var emitted = activations.emitCode(perfil("buyer@test.com", "García"), "PRO");

        try (Connection connection = provider.get(); Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT code_hash, code_enc FROM activations WHERE id=" + emitted.activationId())) {
                assertTrue(rs.next());
                String codeHash = rs.getString("code_hash");
                byte[] codeEnc = rs.getBytes("code_enc");
                assertAll(
                        () -> assertFalse(new String(codeEnc).contains(emitted.code()),
                                "el código no viaja en claro en la fila"),
                        () -> assertEquals(64, codeHash.length(), "guardamos HASH, no el código")
                );
            }
        }

        // lookup del código real lo encuentra; uno falso no:
        assertTrue(activations.lookupActiveCode(emitted.code()).isPresent());
        assertTrue(activations.lookupActiveCode("MP3C-XXXX-YYYY-ZZZZ").isEmpty());
    }

    @Test
    @DisplayName("2.6 'clave única': nueva emisión del mismo correo DESACTIVA el código previo")
    void claveUnica_porUsuario() throws Exception {
        var profile = perfil("unico@test.com", "Díaz");

        var c1 = activations.emitCode(profile, "PRO");
        var c2 = activations.emitCode(profile, "PRO");

        assertAll(
                () -> assertEquals(1, countRows("users"), "sin duplicar usuario"),
                () -> assertEquals(2, countRows("activations")),
                () -> assertNotEquals(c1.code(), c2.code()),
                () -> assertTrue(activations.lookupActiveCode(c2.code()).isPresent(),
                        "el código nuevo sigue ACTIVE"),
                () -> assertTrue(activations.lookupActiveCode(c1.code()).isEmpty(),
                        "el código previo ya NO es usable (clave única)")
        );
    }

    @Test
    @DisplayName("2.6 recuperación: code_enc descifra con correo+apellido correctos; pepper mal falla")
    void recuperacion() throws Exception {
        var profile = perfil("recover@test.com", "Núñez");
        var emitted = activations.emitCode(profile, "PRO");

        assertAll(
                () -> assertEquals(emitted.code(), activations.recoverCode(profile).orElse("x"),
                        "el código se recupera EN CLARO para el mismo usuario"),
                () -> assertTrue(activations.recoverCode(
                        perfil("recover@test.com", "otroApellido")).isEmpty()),
                () -> assertTrue(activations.recoverCode(
                        perfil("nadie@test.com", "Núñez")).isEmpty())
        );
    }

    // --- helpers ---------------------------------------------------------------

    private int countRows(String table) throws Exception {
        try (Connection connection = provider.get(); Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String tierOf(long id) throws Exception {
        try (Connection connection = provider.get(); Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT tier FROM users WHERE id=" + id)) {
                return rs.next() ? rs.getString("tier") : null;
            }
        }
    }
}
