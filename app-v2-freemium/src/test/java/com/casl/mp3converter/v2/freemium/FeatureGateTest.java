package com.casl.mp3converter.v2.freemium;

import com.casl.mp3converter.core.Mp3ConversionService;
import com.casl.mp3converter.core.QualityPolicy;
import com.casl.mp3converter.core.license.LicenseCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La regla central del pay-to-pro local (fases 3/5, requisitos 2.4 y 2.6):
 * {@link FeatureGate#refreshFromLicenseFile()} decide el tier SOLO desde el
 * binario {@code mp3converter.lic} junto al .exe; y el tier manda sobre
 * {@link FeatureGate#createService()} (calidad, watermark, bloques).
 */
@DisplayName("FeatureGate: licencia -> tier -> servicio (requisito 2.4 y 2.6)")
class FeatureGateTest {

    @TempDir
    Path licenseDir;

    @AfterEach
    void clearOverride() {
        System.clearProperty("mp3converter.license.path");
        FeatureGate.setProEnabled(false); // no filtrar estado entre tests
    }

    @Test
    @DisplayName("sin licencia => FREE: servicio 92kbps con watermark y .opus bloqueado")
    void sinLicencia_tierFree() {
        System.setProperty("mp3converter.license.path", licenseDir.toString());

        FeatureGate.refreshFromLicenseFile();

        assertAll(
                () -> assertFalse(FeatureGate.isPro()),
                () -> assertTrue(FeatureGate.footer().contains("Versión gratuita")),
                () -> assertTrue(FeatureGate.footer().contains("92 kbps"))
        );
    }

    @Test
    @DisplayName("licencia PRO válida => PRO: servicio VBR full sin watermark ni bloques")
    void conLicenciaPro_tierPro() throws IOException {
        System.setProperty("mp3converter.license.path", licenseDir.toString());
        Files.write(licenseDir.resolve("mp3converter.lic"),
                LicenseCodec.encode(new LicenseCodec.LicensePayload(
                        "PRO", "hash", "MP3C-TEST", "2026-11-04T10:00:00Z")));

        FeatureGate.refreshFromLicenseFile();

        var command = Mp3ConversionService.buildFfmpegCommand(
                Path.of("in.wav"), Path.of("out.mp3"), QualityPolicy.full());

        assertAll(
                () -> assertTrue(FeatureGate.isPro()),
                () -> assertTrue(FeatureGate.footer().contains("PRO")),
                // el tier Pro construye un servicio full: VBR (sin -b:a fijo):
                () -> assertEquals(-1, command.indexOf("-b:a"))
        );
    }

    @Test
    @DisplayName("licencia TAMPERADA => FREE (degradar, nunca crash): CTA sigue ofreciendo comprar")
    void licenciaCorrupta_tierFree(@TempDir Path dir) throws IOException {
        System.setProperty("mp3converter.license.path", licenseDir.toString());
        byte[] lic = LicenseCodec.encode(new LicenseCodec.LicensePayload(
                "PRO", "hash", "CODE", "now"));
        lic[10] ^= 0x42;
        Files.write(licenseDir.resolve("mp3converter.lic"), lic);

        FeatureGate.refreshFromLicenseFile();

        assertAll(
                () -> assertFalse(FeatureGate.isPro()),
                () -> assertTrue(FeatureGate.buyTitle().contains("Pro")),
                () -> assertTrue(FeatureGate.buyNotice().contains("opus"))
        );
    }

    @Test
    @DisplayName("refreshFromLicenseFile con carpeta INEXISTENTE no lanza y queda FREE")
    void carpetaInexistente_noLanza_netFree() {
        System.setProperty("mp3converter.license.path",
                licenseDir.resolve("no-existe").toAbsolutePath().toString());

        FeatureGate.refreshFromLicenseFile(); // NO debe lanzar

        assertFalse(FeatureGate.isPro());
    }
}
