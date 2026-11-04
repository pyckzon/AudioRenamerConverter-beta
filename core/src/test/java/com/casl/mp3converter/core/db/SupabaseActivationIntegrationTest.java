package com.casl.mp3converter.core.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tag "db-integration": corren contra Supabase/Postgres REAL cuando existen las
 * variables de entorno documentadas en el README (SUPABASE_DB_URL/USER/PWD).
 * Sin credenciales => SKIP automático (regla 4.5 del plan), como las pruebas
 * ffmpeg con tag "integration".
 */
@Tag("db-integration")
@DisplayName("Supabase (Postgres real) - flujo completo sin plaintext (requisito 2.5.2)")
class SupabaseActivationIntegrationTest {

    /** true sólo si hay credenciales Y la conexión abre; si falla el login => skip. */
    private static boolean databaseReachable() {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        if (config == null || !config.isConfigured()) {
            return false;
        }
        try (Connection ignored = config.connect()) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private CustomerProfile perfil() {
        // correo único por ejecución para permitir CI repetidas:
        String run = Long.toHexString(System.currentTimeMillis()) + "@casl-test.com";
        return new CustomerProfile("Test", "Comprador", run, "+52 55 0000 0000");
    }

    @Test
    @DisplayName("Supabase real: emitir código, dedupe por email_hash, 'clave única', sin plaintext")
    void flujoCompletoContraSupabase() throws Exception {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        assumeTrue(databaseReachable(), "sin SUPABASE_DB_* (skip según plan 4.5)");

        ConnectionProvider provider = () -> config.connect();
        var users = new UserRepository(provider);
        var activations = new ActivationRepository(provider);
        var profile = perfil();

        var userId = users.upsertUser(profile, "FREE");
        var code = activations.emitCode(profile, "PRO");

        // 1. recuperación del código con correo+apellido correctos:
        assertEquals(code.code(), activations.recoverCode(profile).orElse(null));

        // 2. fila cruda SIN plaintext (la regla se aplica también en producción):
        try (Connection connection = provider.get(); Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT email_hash, tier FROM users WHERE id=" + userId.id())) {
                assertTrue(rs.next());
                assertAll(
                        () -> assertEquals(64, rs.getString("email_hash").length(), "hash, no email"),
                        () -> assertFalse(rs.getString("email_hash").contains("@"))
                );
            }
        }

        // 3. dedupe real: repetir el upsert NO duplica la cuenta:
        var first = users.upsertUser(profile, "PRO");
        var again = users.upsertUser(profile, "PRO");
        assertEquals(first.id(), again.id());

        // 4. "clave única": la segunda emisión desactiva el primer código:
        assertTrue(activations.lookupActiveCode(code.code()).isPresent());
        var second = activations.emitCode(profile, "PRO");
        assertTrue(activations.lookupActiveCode(code.code()).isEmpty(),
                "el código previo queda DEACTIVATED en Supabase real");
        assertTrue(activations.lookupActiveCode(second.code()).isPresent());
    }
}
