package com.casl.mp3converter.core.license;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requisito 2.6: "activar contrato Pro" => escribir el mp3converter.lic junto
 * al .exe (formato MPCL de fase 3) + rechazo controlado si no se puede.
 */
@DisplayName("LicenseController - activación escribe lic y falla controlado")
class LicenseControllerTest {

    private String previousLicenseProperty;

    @TempDir
    Path licenseDir;

    @AfterEach
    void restoreProperty() {
        if (previousLicenseProperty == null) {
            System.clearProperty("mp3converter.license.path");
        } else {
            System.setProperty("mp3converter.license.path", previousLicenseProperty);
        }
    }

    private void overrideLicensesPath(Path dir) {
        previousLicenseProperty = System.getProperty("mp3converter.license.path");
        System.setProperty("mp3converter.license.path", dir.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("activate() escribe el summary y el verify() lo lee como PRO con el código")
    void activarEscribeVerificablePro() throws Exception {
        overrideLicensesPath(licenseDir);

        var state = LicenseController.activate("MP3C-KV2M-N5XT-8QRW", "hash-correo");

        assertAll(
                () -> assertEquals(LicenseVerifier.Tier.PRO, state.tier()),
                () -> assertEquals("MP3C-KV2M-N5XT-8QRW", state.payload().activationCode()),
                () -> assertTrue(Files.exists(licenseDir.resolve("mp3converter.lic"))),
                () -> {
                    String raw = new String(Files.readAllBytes(licenseDir.resolve("mp3converter.lic")));
                    assertFalse(raw.contains("hash-correo"), "el binario no revela el hash en claro");
                }
        );
    }

    @Test
    @DisplayName("activate() dos veces: la licencia se ACTUALIZA (reescribe sin error)")
    void reactivar_reescribe() {
        overrideLicensesPath(licenseDir);

        LicenseController.activate("MP3C-AAAA-BBBB-CCCC", "h1");
        var second = LicenseController.activate("MP3C-DDDD-EEEE-FFFF", "h2");

        assertAll(
                () -> assertEquals(LicenseVerifier.Tier.PRO, second.tier()),
                () -> assertEquals("MP3C-DDDD-EEEE-FFFF", second.payload().activationCode())
        );
    }

    @Test
    @DisplayName("activate() FALLA controlado: directorio inexistente => estado FREE, NO crash")
    void falloControlado() {
        overrideLicensesPath(licenseDir.resolve("carpeta-inexistente"));

        var state = LicenseController.activate("MP3C-AAAA-BBBB-CCCC", "h");

        assertAll(
                () -> assertEquals(LicenseVerifier.Tier.FREE, state.tier()),
                () -> assertTrue(state.reason().contains("No se pudo escribir"))
        );
    }
}
