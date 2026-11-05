package com.casl.mp3converter.core.license;

import com.casl.mp3converter.core.license.LicenseCodec.LicensePayload;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lee {@code mp3converter.lic} desde el directorio del ejecutable (requisito 2.4/2.6):
 * el binario vive junto al {@code .exe}; su presencia y validez definen el tier.
 * <p>
 * Resolución del directorio:
 * <ol>
 *   <li>propiedad del sistema {@code mp3converter.license.path} (tests/override),</li>
 *   <li>{@code jpackage.app-path} (definida por jpackage en el .exe empaquetado),</li>
 *   <li>directorio de trabajo (execución vía gradle/IDE).</li>
 * </ol>
 */
public final class LicenseVerifier {

    public enum Tier {FREE, PRO}

    /** Result of a verification pass, always with an auditable reason. */
    public record LicenseState(Tier tier, String reason, LicensePayload payload) {
        public static final LicenseState FREE_ABSENT =
                new LicenseState(Tier.FREE, "sin archivo de licencia", null);
    }

    public static final String LICENSE_FILE_NAME = "mp3converter.lic";
    private static final String OVERRIDE_PROPERTY = "mp3converter.license.path";

    private LicenseVerifier() {
    }

    /** Directory where the license file is expected. */
    public static Path licenseDirectory() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            return Path.of(appPath).toAbsolutePath().getParent();
        }
        return Path.of(System.getProperty("user.dir"), ".").toAbsolutePath();
    }

    public static Path licenseFile() {
        return licenseDirectory().resolve(LICENSE_FILE_NAME);
    }

    /** ONE-SHOT verification used at startup (fase 5 re-uses it for hot activation). */
    public static LicenseState verify() {
        Path file = licenseFile();
        if (!Files.exists(file)) {
            return LicenseState.FREE_ABSENT;
        }
        LicensePayload payload = LicenseCodec.read(file);
        if (payload == null) {
            return new LicenseState(Tier.FREE, "licencia inválida o corrupta: " + LICENSE_FILE_NAME, null);
        }
        if (!"PRO".equals(payload.tier())) {
            return new LicenseState(Tier.FREE, "licencia sin permisos PRO", payload);
        }
        return new LicenseState(Tier.PRO, "licencia válida", payload);
    }
}
