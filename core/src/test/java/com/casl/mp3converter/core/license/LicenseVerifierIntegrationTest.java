package com.casl.mp3converter.core.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas de integración del sistema de licencias (requisito 2.4):
 * usan un sistema de archivos real (tempdir) y la resolución de rutas real,
 * incluyendo el override de {@code mp3converter.license.path} para NO tocar
 * el archivo real junto al .exe en entorno de CI.
 * <p>
 * Regla de negocio core del plan: licencia ausente/corrupta/manipulada => tier FREE.
 */
@Tag("integration")
@DisplayName("LicenseVerifier - integración con sistema de archivos (requisito 2.4)")
class LicenseVerifierIntegrationTest {

    /** APRs the override property and returns a restore-hook (Autoclosable-like). */
    private Path setupLicenseDirectory(@TempDir Path dir) {
        String previous = System.getProperty("mp3converter.license.path");
        System.setProperty("mp3converter.license.path", dir.toAbsolutePath().toString());
        return dir;
    }

    private void restore(String previous) {
        if (previous == null) {
            System.clearProperty("mp3converter.license.path");
        } else {
            System.setProperty("mp3converter.license.path", previous);
        }
    }

    @Test
    @DisplayName("licenseDirectory() respeta el override de system property (tests/CI)")
    void licenseDirectory_usaOverride() {
        @SuppressWarnings("unchecked")
        String previous = System.getProperty("mp3converter.license.path");
        try {
            System.setProperty("mp3converter.license.path", "C:/fake/dir");
            assertEquals(Path.of("C:/fake/dir"), LicenseVerifier.licenseDirectory());
            assertEquals(Path.of("C:/fake/dir").resolve(LicenseVerifier.LICENSE_FILE_NAME),
                    LicenseVerifier.licenseFile());
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("licencia ausente => FREE con razón 'sin archivo de licencia'")
    void absentLicense_tierFree(@TempDir Path dir) {
        String previous = System.getProperty("mp3converter.license.path");
        setupLicenseDirectory(dir);
        try {
            var state = LicenseVerifier.verify();

            assertAll(
                    () -> assertEquals(LicenseVerifier.Tier.FREE, state.tier()),
                    () -> assertTrue(state.reason().contains("sin archivo")),
                    () -> assertNull(state.payload())
            );
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("licencia válida PRO => tier PRO o payload completo accesible")
    void validProLicense_tierPro(@TempDir Path dir) throws IOException {
        String previous = System.getProperty("mp3converter.license.path");
        setupLicenseDirectory(dir);
        try {
            Files.write(LicenseVerifier.licenseFile(), LicenseCodec.encode(
                    new LicenseCodec.LicensePayload("PRO", "correo-renderizado-hash",
                            "MP3C-ACT-TEST", "2026-11-04T10:00:00Z")));

            var state = LicenseVerifier.verify();

            assertAll(
                    () -> assertEquals(LicenseVerifier.Tier.PRO, state.tier()),
                    () -> assertEquals("correo-renderizado-hash", state.payload().emailHash()),
                    () -> assertEquals("MP3C-ACT-TEST", state.payload().activationCode()),
                    () -> assertTrue(state.reason().contains("válida"))
            );
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("licencia CORRUPTA (garbage) => FREE (regla del plan: degradar, nunca crash)")
    void garbageLicense_tierFree(@TempDir Path dir) throws IOException {
        String previous = System.getProperty("mp3converter.license.path");
        setupLicenseDirectory(dir);
        try {
            Files.write(LicenseVerifier.licenseFile(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

            var state = LicenseVerifier.verify();

            assertEquals(LicenseVerifier.Tier.FREE, state.tier());
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("licencia TAMPERADA: un byte alterado del binario real => FREE")
    void tamperedLicense_tierFree(@TempDir Path dir) throws IOException {
        String previous = System.getProperty("mp3converter.license.path");
        setupLicenseDirectory(dir);
        try {
            byte[] lic = LicenseCodec.encode(new LicenseCodec.LicensePayload(
                    "PRO", "hash", "CODE", "2026-11-04T10:00:00Z"));
            lic[Math.min(15, lic.length - 1)] ^= 0x10;   // un solo byte del cuerpo
            Files.write(LicenseVerifier.licenseFile(), lic);

            var state = LicenseVerifier.verify();

            assertAll(
                    () -> assertEquals(LicenseVerifier.Tier.FREE, state.tier()),
                    () -> assertTrue(state.reason().contains("inválida"))
            );
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("esquema round: la licencia escrita por LicenseCodec es la única rama PRO posible")
    void validLicenseExactRoundtrip_tierPro(@TempDir Path dir) throws IOException {
        String previous = System.getProperty("mp3converter.license.path");
        setupLicenseDirectory(dir);
        try {
            // El invariante "tier != PRO es rechazado" vive en LicenseCodecTest
            // (deserialize lo lanza en construcción), así que la ÚNICA forma de
            // producir un archivo válido es con un payload PRO legítimo:
            Files.write(LicenseVerifier.licenseFile(), LicenseCodec.encode(
                    new LicenseCodec.LicensePayload("PRO", "h", "c", "now")));

            var state = LicenseVerifier.verify();
            assertEquals(LicenseVerifier.Tier.PRO, state.tier());
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("carpeta de licencia INEXISTENTE en el override también degrada a FREE (no lanza)")
    void nonExistentDirectory_tierFree(@TempDir Path empty) {
        String previous = System.getProperty("mp3converter.license.path");
        System.setProperty("mp3converter.license.path",
                empty.resolve("no-existe-esta-carpeta").toAbsolutePath().toString());
        try {
            var state = LicenseVerifier.verify();
            assertEquals(LicenseVerifier.Tier.FREE, state.tier());
        } finally {
            restore(previous);
        }
    }

    /** APR título del commit: "licencia identidad + integridad a través del ciclo completo". */
    @Test
    @DisplayName("ciclo completo: encode→file→verify refleja integridad + confidencialidad")
    void cicloCompleto_codificar_Escribir_Verificar(@TempDir Path dir) throws IOException {
        String previous = System.getProperty("mp3converter.license.path");
        setupLicenseDirectory(dir);
        try {
            byte[] lic = LicenseCodec.encode(new LicenseCodec.LicensePayload(
                    "PRO", "sha256(correo)", "MP3C-COD", "2026-11-04T12:00:00Z"));
            Files.write(LicenseVerifier.licenseFile(), lic);

            // el archivo real en disco NO revela el tier en texto plano:
            byte[] raw = Files.readAllBytes(LicenseVerifier.licenseFile());
            var fileText = new String(raw);
            assertFalse(fileText.contains("PRO"), "el binario no debe revelar el tier");
            assertFalse(fileText.contains("MP3C-COD"), "el binario no debe revelar el código");

            var state = LicenseVerifier.verify();
            assertEquals(LicenseVerifier.Tier.PRO, state.tier());
            assertEquals("MP3C-COD", state.payload().activationCode());
        } finally {
            restore(previous);
        }
    }
}
