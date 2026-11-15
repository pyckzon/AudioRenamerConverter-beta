package com.casl.mp3converter.core.license;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * El "dueño" del ciclo de vida de la licencia local (requisito 2.6):
 * {@link #activate} genera (con LicenseCodec) y escribe {@code mp3converter.lic}
 * junto al .exe, como "pasaporte" del contrato Pro; la activación en caliente
 * (sin reiniciar) la resuelven los llamadores (FeatureGate en la app v2).
 */
public final class LicenseController {

    private static final Logger LOGGER = Logger.getLogger(LicenseController.class.getName());

    private LicenseController() {
    }

    /**
     * Escribe la licencia PRO (formato MPCL de la Fase 3: no transparente) en el
     * archivo estándar {@link LicenseVerifier#licenseFile()}; atómico si el SO lo
     * permite (mover temporal es opcional). Devuelve el estado verificado re-leyendo
     * el archivo (invariante: lo escrito SIEMPRE se legible devolver).
     */
    public static LicenseVerifier.LicenseState activate(String activationCode, String emailHash) {
        LicenseCodec.LicensePayload payload = new LicenseCodec.LicensePayload(
                "PRO",
                emailHash == null ? "" : emailHash,
                activationCode == null ? "" : activationCode,
                Instant.now().toString());
        try {
            byte[] bytes = LicenseCodec.encode(payload);
            writeAtomically(bytes);
            LOGGER.info("Licencia Pro escrita de forma segura (MPCL, AES-GCM+HMAC).");
        } catch (Exception e) {
            LOGGER.warning("No se pudo escribir la licencia: " + e);
            // no tira: la app queda en el tier actual (FREE), probado en tests:
            return new LicenseVerifier.LicenseState(
                    LicenseVerifier.Tier.FREE, "No se pudo escribir la licencia de activación", null);
        }
        return LicenseVerifier.verify();
    }

    private static void writeAtomically(byte[] bytes) throws IOException {
        var target = LicenseVerifier.licenseFile();
        var temp = Files.createTempFile(target.getParent(), "mp3converter-lic-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
