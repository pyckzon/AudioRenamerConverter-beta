package com.casl.mp3converter.v2.freemium;

import com.casl.mp3converter.core.Mp3ConversionService;
import com.casl.mp3converter.core.QualityPolicy;
import com.casl.mp3converter.core.db.CustomerProfile;
import com.casl.mp3converter.core.license.FieldCrypto;
import com.casl.mp3converter.core.license.LicenseController;
import com.casl.mp3converter.core.license.LicenseVerifier;
import com.casl.mp3converter.core.payment.MockPaymentGateway;
import com.casl.mp3converter.core.payment.PaymentFlow;
import com.casl.mp3converter.core.payment.PaymentRequest;
import com.casl.mp3converter.core.payment.PurchaseOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FASE 6 — Test de fila E2E en JVM local (criterio del plan):
 * <ol>
 *   <li>Arranque v2 sin licencia => FeatureGate FREE.</li>
 *   <li>fila: compra (PaymentFlow validación+mock) => activación
 *       ({@link LicenseController}) => FeatureGate PRO en caliente (sin reiniciar)
 *       => conversiones REALES por tier con ffmpeg (.opus incluido en PRO).</li>
 *   <li>Regreso a FREE cuando se elimina la licencia.</li>
 *   <li> MULTI-instancia: dos carpetas de exe con licencias distintas.</li>
 * </ol>
 * Requiere ffmpeg en el PATH; si no está, se omiten (no fallan).
 */
@Tag("integration")
@DisplayName("Fase 6 E2E: compra -> activación -> PRO -> regreso a FREE (conversión real)")
class AppV2PurchaseToProE2ETest {

    private static final CustomerProfile PERFIL =
            new CustomerProfile("AnaLaura", "García", "e2e.v2@test.com", "5512345678");
    private static final String DIRECCION = "Av. Futuro #123";
    private static final PaymentRequest TARJETA =
            new PaymentRequest("AnaLaura García", "4111111111111111", "12/2099", "123");

    private String prevLicenseProperty;
    private String prevAppPathProperty;

    @TempDir
    Path tempData; // se usan ambos: licDir y datos de conversión

    @AfterEach
    void resetSystemProperties() {
        if (prevLicenseProperty == null) {
            System.clearProperty("mp3converter.license.path");
        } else {
            System.setProperty("mp3converter.license.path", prevLicenseProperty);
        }
        if (prevAppPathProperty == null) {
            System.clearProperty("jpackage.app-path");
        } else {
            System.setProperty("jpackage.app-path", prevAppPathProperty);
        }
        FeatureGate.setProEnabled(false);
    }

    private Path licensePathOverride(String name) {
        Path dir = tempData.resolve(name);
        try {
            Files.createDirectories(dir); // Files.createTempFile exige que exista
        } catch (IOException e) {
            throw new IllegalStateException("no se pudo preparar " + dir, e);
        }
        prevLicenseProperty = System.getProperty("mp3converter.license.path");
        prevAppPathProperty = System.getProperty("jpackage.app-path");
        System.setProperty("mp3converter.license.path", dir.toString());
        // el override de app-path NO debe interponer: la decisión es del override:
        System.clearProperty("jpackage.app-path");
        return dir;
    }

    @Test
    @DisplayName("fila E2E completa: FREE -> compra -> activación -> PRO (*.opus) -> borrar lic -> FREE")
    void compraActivacionYRegresoAFree() throws Exception {
        assumeTrue(new Mp3ConversionService().isFfmpegAvailable(),
                "ffmpeg no disponible en esta máquina: la fila E2E se omite");

        Path exeDir = licensePathOverride("exe-gratis");

        // --- 1. arranque v2 SIN licencia: FREE -------------------------------
        FeatureGate.refreshFromLicenseFile();
        assertFalse(FeatureGate.isPro(), "sin lic: FREE");
        var serviceFree = FeatureGate.createService();

        // afuera .wav que convierte bien en FREE (92 kbps + watermark):
        Path source = Files.createDirectories(tempData.resolve("src-free"));
        Path dest = Files.createDirectories(tempData.resolve("dst-free"));
        createWav(source, "tono-free.wav", 0.4);
        var summaryFree = serviceFree.convert(source, dest, (p, t, f) -> { });
        assertAll("conversión FREE",
                () -> assertEquals(1, summaryFree.converted()),
                () -> assertEquals(0, summaryFree.skipped()),
                () -> assertTrue(Files.size(dest.resolve("tono-free.mp3")) > 1000)
        );

        // --- 2. fila de compra + activación (la UI hace lo mismo en caliente) ---
        var flow = new PaymentFlow(new MockPaymentGateway(), null); // demo sin BD
        PurchaseOutcome outcome = flow.purchase(PERFIL, DIRECCION, TARJETA);
        assertEquals(PurchaseOutcome.Status.PURCHASED, outcome.status());
        assertTrue(outcome.codePersisted() == false, "demo sin BD: código efímero");

        var state = LicenseController.activate(
                outcome.activationCode(), FieldCrypto.emailHash(PERFIL.correo()));
        assertEquals(LicenseVerifier.Tier.PRO, state.tier());

        // el hot flip del requisito 2.6 (lo que hace el callback de ControllerV2):
        FeatureGate.setProEnabled(state.tier() == LicenseVerifier.Tier.PRO);
        assertTrue(FeatureGate.isPro(), "PRO en caliente, sin reiniciar");
        assertTrue(existsLicense(exeDir), "la lic se genera junto al .exe");
        assertTrue(FeatureGate.headerTitle().contains("PRO"));

        // --- 3. PRO: VBR y .opus AHORA sí convierte (sin bloqueos) -------------
        var servicePro = FeatureGate.createService();
        Path srcOpus = Files.createDirectories(tempData.resolve("src-pro"));
        Path dstOpus = Files.createDirectories(tempData.resolve("dst-pro"));

        Path opus = srcOpus.resolve("tono.opus");
        assumeTrue(generateOpus(opus), "libopus ausente en esta build de ffmpeg: se omite");
        createWav(srcOpus, "tono-pro.wav", 0.4);

        var summaryPro = servicePro.convert(srcOpus, dstOpus, (p, t, f) -> { });
        assertAll("conversión PRO: WAV + OPUS",
                () -> assertEquals(2, summaryPro.converted()),
                () -> assertEquals(0, summaryPro.skipped(), "en PRO ningún archivo saltado"),
                () -> assertTrue(Files.size(dstOpus.resolve("tono-pro.mp3")) > 1000),
                () -> assertTrue(Files.size(dstOpus.resolve("tono.mp3")) > 1000)
                );

        // --- 4. regreso a FREE al ELIMINAR la licencia --------------------------
        Files.deleteIfExists(exeDir.resolve("mp3converter.lic"));
        FeatureGate.refreshFromLicenseFile();
        assertAll("regreso a FREE: reglas del tier aplicadas",
                () -> assertFalse(FeatureGate.isPro()),
                () -> assertTrue(FeatureGate.headerTitle().contains("gratuita")),
                () -> {
                    Files.createDirectories(tempData.resolve("dst-free2"));
                    assertEquals(1, serviceFree.convert(
                            srcOpus, tempData.resolve("dst-free2"), (p, t, f) -> { }).converted(),
                            "en FREE solo el .wav: el .opus queda bloqueado");
                }
        );
    }

    @Test
    @DisplayName("MULTI-INSTANCIA: dos carpetas de exe viven licencias DESIGUALES")
    void multiInstancia_licenciasIndepedientes() throws Exception {
        // carpeta "exe-A": compra y activa (PRO)
        Path exeA = licensePathOverride("exe-A");
        var stateA = LicenseController.activate("MP3C-AAAA-0000-0000", "hashA");
        assertEquals(LicenseVerifier.Tier.PRO, stateA.tier());

        // carpeta "exe-B": SIN licencia (FREE)
        Path exeB = licensePathOverride("exe-B");

        // cada proceso (otra instancia) VERIFICA solo SU carpeta:
        System.setProperty("mp3converter.license.path", exeB.toString());
        var bajo = LicenseVerifier.verify().tier();
        System.setProperty("mp3converter.license.path", exeA.toString());
        var alto = LicenseVerifier.verify();

        assertAll("multi-instancia",
                () -> assertEquals(LicenseVerifier.Tier.FREE, bajo,
                        "exe-B sin lic: su propia verificación da FREE"),
                () -> assertEquals(LicenseVerifier.Tier.PRO, alto.tier(),
                        "exe-A (PRO) no sirve a exe-B: licencias por carpeta"),
                () -> assertTrue(existsLicense(exeA) && !existsLicense(exeB),
                        "cada carpeta tiene SU estado")
        );
        // y el arranque de la app respeta la propia carpeta (requisito arranque 2.4):
        FeatureGate.refreshFromLicenseFile();
        assertTrue(FeatureGate.isPro(), "el arranque de A abre PRO desde su lic");
    }

    // --- helpers -----------------------------------------------------------------

    private static boolean existsLicense(Path exeDir) {
        return Files.exists(exeDir.resolve("mp3converter.lic"));
    }

    /** Genera un WAV PCM 16 bits mono 440 Hz (valid, sin ffmpeg). */
    private static Path createWav(Path dir, String fileName, double seconds) throws IOException {
        int sampleRate = 44_100;
        int sampleCount = (int) (sampleRate * seconds);
        int bitsPerSample = 16;
        int channels = 1;
        int bytesPerSample = bitsPerSample / 8;
        int dataLen = sampleCount * channels * bytesPerSample;
        int chunkSize = 36 + dataLen;

        var buffer = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(chunkSize);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * bytesPerSample);
        buffer.putShort((short) (channels * bytesPerSample));
        buffer.putShort((short) bitsPerSample);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataLen);
        for (int i = 0; i < sampleCount; i++) {
            buffer.putShort((short) (7000 * Math.sin(2 * Math.PI * 440 * i / sampleRate)));
        }
        return Files.write(dir.resolve(fileName), buffer.array());
    }

    /** Genera un .opus sintético con libopus (false si el encoder no existe). */
    private static boolean generateOpus(Path target) {
        try {
            Process p = new ProcessBuilder("ffmpeg",
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=0.5",
                    "-c:a", "libopus", "-b:a", "96k",
                    target.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
