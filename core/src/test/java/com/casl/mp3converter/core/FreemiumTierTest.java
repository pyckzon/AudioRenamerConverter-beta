package com.casl.mp3converter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freemium tier rules (requisitos 2.1, 2.2, 2.3) at unit level, WITHOUT ffmpeg:
 * the watermark pipeline, the blocked .opus gate and the "ffmpeg never invoked"
 * guarantee for blocked files.
 */
@DisplayName("Freemium: watermark y gate de .opus (requisitos 2.1-2.3)")
class FreemiumTierTest {

    // --- Watermark pipeline command -------------------------------------------

    @Test
    @DisplayName("2.2 el free tier inserta el watermark al INICIO y al FIN (concat x2 con fades)")
    void watermarkCommand_insertaWatermarkInicioFin() {
        var command = Mp3ConversionService.buildFfmpegCommandWithWatermark(
                Path.of("origen", "cancion.wav"),
                Path.of("destino", "cancion.mp3"),
                QualityPolicy.freeTier92(),
                Path.of("assets", "watermark-mp3converter.wav"));

        var filterArg = command.get(command.indexOf("-filter_complex") + 1);
        assertAll(
                () -> assertEquals(List.of("origen\\cancion.wav", "assets\\watermark-mp3converter.wav"),
                        List.of(command.get(command.indexOf("-i") + 1),
                                command.get(command.lastIndexOf("-i") + 1)),
                        "dos entradas: el origen y el asset del watermark"),
                // la cadena de concat añade la fuente primero y el asset al final, dos veces:
                () -> assertEquals(2, filterArg.split("concat=n=2:v=0:a=1").length - 1,
                        "se inserta el asset dos veces: inicio y fin"),
                () -> assertTrue(filterArg.contains("afade"), "el asset debe tener fade para no hacer click"),
                () -> assertTrue(command.indexOf("libmp3lame") < command.indexOf("-b:a")
                        && command.indexOf("-b:a") < command.size() - 1 && command.indexOf("92k") >= 0,
                        "la calidad free (-b:a 92k) sigue siendo obligatoria dentro del pipeline"),
                () -> assertEquals("92k", command.get(command.indexOf("-b:a") + 1))
        );
    }

    @Test
    @DisplayName("la versión Pro (sin watermark) jamás incluye el pipeline del watermark")
    void proPolicy_sinWatermarkNunca() {
        var command = Mp3ConversionService.buildFfmpegCommand(
                Path.of("origen", "cancion.wav"), Path.of("destino", "cancion.mp3"), QualityPolicy.full());

        assertAll(
                () -> assertFalse(command.contains("-filter_complex")),
                () -> assertFalse(command.contains("-b:a"), "pro usa VBR, no CBR fijo"),
                () -> assertTrue(!command.stream().anyMatch(arg -> arg.contains("watermark")))
        );
    }

    // --- Blocked .opus gate ------------------------------------------------------

    /** Service that counts how many files would reach ffmpeg (stubbed conversion). */
    private static Mp3ConversionService stubbedService(QualityPolicy policy, Path watermark,
                                                       Set<String> blocked, AtomicInteger ffmpegCalls) {
        return new Mp3ConversionService(policy, watermark, blocked) {
            @Override
            public boolean isFfmpegAvailable() {
                return true;
            }

            @Override
            String convertSingleFile(Path audioFile, Path destDir, Set<Path> outputsInRun) {
                ffmpegCalls.incrementAndGet();
                return null;
            }
        };
    }

    @Test
    @DisplayName("2.3 el free tier lista los .opus como 'requiere la versión Pro' sin invocar ffmpeg")
    void freeTierOpus_bloqueado_y_listado(@TempDir Path sourceDir) throws Exception {
        AtomicInteger ffmpegCalls = new AtomicInteger();
        var service = stubbedService(QualityPolicy.freeTier92(), Path.of("wm.wav"), Set.of("opus"), ffmpegCalls);

        Files.writeString(sourceDir.resolve("cancion.opus"), "opus falso");
        Files.writeString(sourceDir.resolve("vale.wav"), "wav falso");

        AtomicInteger processed = new AtomicInteger();
        var summary = service.convert(sourceDir, sourceDir,
                (p, t, f) -> processed.incrementAndGet());

        assertAll(
                () -> assertEquals(1, summary.converted(), "solo el wav entra al pipeline"),
                () -> assertEquals(1, summary.skipped(), "el opus queda como omitido"),
                () -> assertEquals(1, ffmpegCalls.get(),
                        "NUNCA debe invocarse el conversor para los .opus bloqueados"),
                () -> assertEquals(2, processed.get(), "el opus SÍ se lista (progreso) aunque no se convierta"),
                () -> assertTrue(summary.skippedFiles().get(0).contains("cancion.opus")),
                () -> assertTrue(summary.skippedFiles().get(0).contains("requiere la versión Pro"),
                        "el mensaje debe pedir la versión Pro")
        );
    }

    @Test
    @DisplayName("sin bloqueo (tier completo), el .opus sí llega al pipeline")
    void tierCompletoOpus_convertible(@TempDir Path sourceDir) throws Exception {
        AtomicInteger ffmpegCalls = new AtomicInteger();
        var service = stubbedService(QualityPolicy.full(), null, Set.of(), ffmpegCalls);

        Files.writeString(sourceDir.resolve("cancion.opus"), "opus falso");
        var summary = service.convert(sourceDir, sourceDir, (p, t, f) -> { });

        assertAll(
                () -> assertEquals(1, summary.converted()),
                () -> assertEquals(1, ffmpegCalls.get())
        );
    }
}
