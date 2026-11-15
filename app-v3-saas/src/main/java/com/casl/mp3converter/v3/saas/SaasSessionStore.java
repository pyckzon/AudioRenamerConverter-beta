package com.casl.mp3converter.v3.saas;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * FASE 9.2 — Sesión local del SaaS (el equivalente de la .lic de v2):
 * un archivo {@code mp3converter.saas} JUNTO AL .EXE con el token de licencia,
 * la ventana de gracia vigente y el correo de la cuenta. NUNCA guarda la
 * password ni el JWT de 15 minutos (sólo el token de larga vida).
 * <p>
 * Resolución de la ruta (mismo patrón que LicenseVerifier de v2):
 * system property {@code mp3converter.saas.session} (tests) → carpeta del
 * .exe ({@code jpackage.app-path}) → directorio de trabajo.
 */
public final class SaasSessionStore {

    private static final Logger LOGGER = Logger.getLogger(SaasSessionStore.class.getName());
    private static final String FILE_NAME = "mp3converter.saas";

    /** Estado persistido de la sesión de suscripción. */
    public record Session(String token, Instant graceUntil, String email) { }

    private SaasSessionStore() {
    }

    private static Path path() {
        String override = System.getProperty("mp3converter.saas.session");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            return Path.of(appPath).resolve(FILE_NAME);
        }
        return Path.of(FILE_NAME); // dev: directorio de trabajo
    }

    public static void save(Session session) {
        Properties props = new Properties();
        props.setProperty("token", session.token());
        props.setProperty("graceEpochMillis", Long.toString(session.graceUntil().toEpochMilli()));
        props.setProperty("email", session.email() == null ? "" : session.email());
        try (OutputStream out = Files.newOutputStream(path())) {
            props.store(out, "MP3Converter SaaS session (token + ventana de gracia)");
        } catch (IOException e) {
            // NUNCA lanza: sin escritura la app queda en el estado vigente y el
            // usuario lo verá en la UI (mismo espíritu defensivo que v2).
            LOGGER.warning("No se pudo guardar la sesión SaaS: " + e);
        }
    }

    public static Optional<Session> load() {
        Path file = path();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            String token = props.getProperty("token");
            String grace = props.getProperty("graceEpochMillis");
            if (token == null || token.isBlank() || grace == null) {
                return Optional.empty(); // corrupto = sin sesión (nunca lanza)
            }
            return Optional.of(new Session(token,
                    Instant.ofEpochMilli(Long.parseLong(grace)),
                    props.getProperty("email", "")));
        } catch (Exception e) {
            LOGGER.warning("Sesión SaaS ilegible (se ignora): " + e);
            return Optional.empty();
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(path());
        } catch (IOException e) {
            LOGGER.warning("No se pudo borrar la sesión SaaS: " + e);
        }
    }
}
