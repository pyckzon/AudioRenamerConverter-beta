package com.casl.mp3converter.core.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Suiza del esquema: ejecuta las migraciones versionadas (recurso
 * {@code /db/migration/V1__init.sql}) de forma idempotente al primer uso.
 * Flyway queda estacionado: con una sola migración y :memory: en tests,
 * un inicializador simple es suficiente (decisión documentada en README).
 */
public final class SchemaInitializer {

    private static final Logger LOGGER = Logger.getLogger(SchemaInitializer.class.getName());

    private SchemaInitializer() {
    }

    /** Ejecuta el/los scripts una vez; seguro de re-invocar. */
    public static void ensureSchema(Connection connection) throws SQLException {
        ensureSchema(connection, "/db/migration/V1__init.sql");
    }

    /**
     * Igual que {@link #ensureSchema(Connection)} pero con una migración
     * arbitraria del classpath (la usa backend-saas con su esquema {@code saas}).
     * ATENCIÓN: parte el script por punto y coma — sin punto y coma en comentarios.
     */
    public static void ensureSchema(Connection connection, String resource) throws SQLException {
        String script = readMigration(resource);
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                String clean = sql.strip();
                if (!clean.isEmpty()) {
                    statement.execute(clean);
                }
            }
        }
        LOGGER.fine("Esquema verificado (migraciones idempotentes aplicadas).");
    }

    private static String readMigration(String resource) {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("migración no encontrada: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("migración ilegible: " + resource, e);
        }
    }
}
