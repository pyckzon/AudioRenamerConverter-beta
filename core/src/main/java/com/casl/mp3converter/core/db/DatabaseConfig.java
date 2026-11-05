package com.casl.mp3converter.core.db;

import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configuración de la base de datos (requisito fase 4, punto 4):
 * NUNCA hardcoded — variables de entorno {@code SUPABASE_DB_URL},
 * {@code SUPABASE_DB_USER}, {@code SUPABASE_DB_PWD}; para tests/develop
 * se aceptan system properties {@code mp3converter.db.url/user/pwd}.
 *
 * <p>Decision documentada en README: la política de conexión es una conexión por
 * operación (HikariCP queda fuera porque una app de escritorio abre 0..1
 * conexiones en su vida útil y el pooling agrega dependencias modulares al jlink).</p>
 */
public final class DatabaseConfig {

    private static final Logger LOGGER = Logger.getLogger(DatabaseConfig.class.getName());

    private final String url;
    private final String user;
    private final String password;

    private DatabaseConfig(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** url/user/pwd de env (SUPABASE_*) o de system properties; null si no hay configuración. */
    public static DatabaseConfig fromEnvironment() {
        String url = prop("mp3converter.db.url", "SUPABASE_DB_URL");
        String user = prop("mp3converter.db.user", "SUPABASE_DB_USER");
        String password = prop("mp3converter.db.pwd", "SUPABASE_DB_PWD");
        if (url == null || url.isBlank() || user == null || user.isBlank()) {
            LOGGER.info("Sin SUPABASE_DB_URL/USER: la base de datos queda deshabilitada.");
            return null;
        }
        return new DatabaseConfig(url, user, password == null ? "" : password);
    }

    private static String prop(String systemProperty, String envVar) {
        String value = System.getProperty(systemProperty);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return System.getenv(envVar);
    }

    public boolean isConfigured() {
        return true;
    }

    public String url() {
        return url;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** Abre una conexión nueva por llamada. Nunca la password en logs. */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** proveedor listo para repositorios, sin exponer tipos SQL fuera del core. */
    public ConnectionProvider connectionProvider() {
        return this::connect;
    }

    /** Máscara segura del endpoint (host only) para logs/diálogos. */
    public String maskedDescription() {
        try {
            int schemeEnd = url.indexOf("://");
            int hostStart = schemeEnd < 0 ? 0 : schemeEnd + 3;
            int hostEnd = url.indexOf('/', hostStart);
            String host = hostEnd < 0 ? url.substring(hostStart) : url.substring(hostStart, hostEnd);
            return "postgres://" + host + "/<bd>";
        } catch (RuntimeException e) {
            return "postgres://<bd>";
        }
    }

    @Override
    public String toString() {
        // NUNCA la password en el toString: lo obliga el requisito del plan.
        return "DatabaseConfig{" + maskedDescription() + "}";
    }
}
