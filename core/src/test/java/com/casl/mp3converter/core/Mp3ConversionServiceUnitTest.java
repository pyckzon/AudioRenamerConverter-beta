package com.casl.mp3converter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link Mp3ConversionService}: fast and fully deterministic.
 * They never invoke the real ffmpeg: availability and the per-file conversion
 * are stubbed, so every branch of the service logic can be covered independent
 * of the environment. The real ffmpeg runs are covered by the integration tests.
 */
@DisplayName("Mp3ConversionService - pruebas unitarias")
class Mp3ConversionServiceUnitTest {

    private final Mp3ConversionService service = new Mp3ConversionService();

    // --- Stub helpers ------------------------------------------------------

    /** Service with ffmpeg availability forced and the per-file conversion stubbed. */
    private static Mp3ConversionService serviceWithFfmpeg(boolean available,
                                                          String stubbedConversionResult) {
        return new Mp3ConversionService() {
            @Override
            boolean isFfmpegAvailable() {
                return available;
            }

            @Override
            String convertSingleFile(Path audioFile, Path destDir) throws IOException {
                return stubbedConversionResult;
            }
        };
    }

    /** Progress listener that records every call in the returned list. */
    private static List<String> listening(Mp3ConversionService.ProgressListener[] holder) {
        List<String> calls = new ArrayList<>();
        holder[0] = (processed, total, fileName) -> calls.add(processed + "/" + total + ":" + fileName);
        return calls;
    }

    // --- Supported extensions --------------------------------------------------

    @Test
    @DisplayName("isSupported reconoce las extensiones de audio soportadas")
    void isSupported_reconoceFormatosSoportados() {
        assertAll(
                () -> assertTrue(service.isSupported("cancion.wav")),
                () -> assertTrue(service.isSupported("GRABACION.WAV")),
                () -> assertTrue(service.isSupported("microfono.aif")),
                () -> assertTrue(service.isSupported("piano.aiff")),
                () -> assertTrue(service.isSupported("album.flac")),
                () -> assertTrue(service.isSupported("voz.m4a")),
                () -> assertTrue(service.isSupported("podcast.ogg")),
                () -> assertTrue(service.isSupported("dictado.wma"))
        );
    }

    @Test
    @DisplayName("isSupported rechaza archivos que no son audio convertible")
    void isSupported_rechazaNoAudio() {
        assertAll(
                () -> assertFalse(service.isSupported("documento.txt")),
                () -> assertFalse(service.isSupported("captura.png")),
                // mp3 as source is deliberately unsupported: no re-converting already-mp3 files.
                () -> assertFalse(service.isSupported("cancion.mp3")),
                () -> assertFalse(service.isSupported("VIDEO.MP4")),
                // Corner cases.
                () -> assertFalse(service.isSupported("sinpunto")),
                () -> assertFalse(service.isSupported("puntofinal.")),
                () -> assertFalse(service.isSupported(".gitignore")),
                () -> assertFalse(service.isSupported(""))
        );
    }

    @Test
    @DisplayName("stripExtension quita la extensión conservando el resto del nombre")
    void stripExtension_variosCasos() {
        assertAll(
                () -> assertEquals("cancion", service.stripExtension("cancion.wav")),
                () -> assertEquals("cancion", service.stripExtension("cancion")),
                () -> assertEquals("mi.grabacion.2026", service.stripExtension("mi.grabacion.2026.flac")),
                () -> assertEquals(".gitignore", service.stripExtension(".gitignore"))
        );
    }

    // --- ConversionSummary -------------------------------------------------------

    @Test
    @DisplayName("ConversionSummary calcula los totales")
    void conversionSummary_calculaTotales() {
        var summary = new Mp3ConversionService.ConversionSummary(3, 2, List.of("a.wav"));
        assertAll(
                () -> assertEquals(5, summary.totalFiles()),
                () -> assertFalse(summary.noFilesFound())
        );

        var empty = new Mp3ConversionService.ConversionSummary(0, 0, List.of());
        assertAll(
                () -> assertEquals(0, empty.totalFiles()),
                () -> assertTrue(empty.noFilesFound())
        );
    }

    // --- convert(): branches without touching the real ffmpeg -------------------

    @Test
    @DisplayName("convert sin ffmpeg explica claramente el problema en vez de crashear")
    void convert_sinFfmpeg_mensajeClaro() {
        var withoutFfmpeg = serviceWithFfmpeg(false, null);

        var exception = assertThrows(Mp3ConversionService.ConversionServiceException.class,
                () -> withoutFfmpeg.convert(Path.of("origen"), Path.of("destino"),
                        (p, t, f) -> { }));

        var message = exception.getMessage();
        assertAll(
                () -> assertTrue(message.toLowerCase().contains("ffmpeg")),
                () -> assertTrue(message.contains("winget install ffmpeg"))
        );
    }

    @Test
    @DisplayName("convert con carpeta origen vacía termina con un resumen vacío")
    void convert_carpetaVacia_resumenVacio(@TempDir Path sourceDir) throws Exception {
        var spy = serviceWithFfmpeg(true, null);

        Mp3ConversionService.ProgressListener[] holder = new Mp3ConversionService.ProgressListener[1];
        var calls = listening(holder);
        var summary = spy.convert(sourceDir, sourceDir, holder[0]);

        assertAll(
                () -> assertTrue(summary.noFilesFound()),
                () -> assertTrue(calls.isEmpty())
        );
    }

    @Test
    @DisplayName("convert ignora archivos no soportados y notifica el progreso correcto")
    void convert_ignoraNoSoportados_yNotificaProgreso(@TempDir Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("cancion.wav"), "audio falso");
        Files.writeString(sourceDir.resolve("notas.txt"), "no es audio");
        Files.writeString(sourceDir.resolve("yaconvertida.mp3"), "mp3 no se reconvierte");
        Files.writeString(sourceDir.resolve("VIDEO.MP4"), "video");

        var spy = serviceWithFfmpeg(true, null); // stub: the conversion "succeeds"

        Mp3ConversionService.ProgressListener[] holder = new Mp3ConversionService.ProgressListener[1];
        var calls = listening(holder);
        var summary = spy.convert(sourceDir, sourceDir, holder[0]);

        assertAll(
                () -> assertEquals(1, summary.converted(), "solo cancion.wav se debe convertir"),
                () -> assertEquals(0, summary.skipped()),
                () -> assertEquals(List.of("1/1:cancion.wav"), calls)
        );
    }

    @Test
    @DisplayName("convert informa cada omisión con su archivo y motivo")
    void convert_informaOmitidosConMotivo(@TempDir Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("buena.wav"), "audio");
        Files.writeString(sourceDir.resolve("rota.wav"), "audio roto");

        var stub = serviceWithFfmpeg(true, "tiempo de espera agotado"); // both files "fail"

        var summary = stub.convert(sourceDir, sourceDir, (p, t, f) -> { });

        assertAll(
                () -> assertEquals(0, summary.converted()),
                () -> assertEquals(2, summary.skipped()),
                () -> assertEquals(2, summary.skippedFiles().size()),
                () -> assertTrue(summary.skippedFiles().stream()
                        .anyMatch(reason -> reason.startsWith("buena.wav: "))),
                () -> assertTrue(summary.skippedFiles().stream()
                        .anyMatch(reason -> reason.startsWith("rota.wav: ")))
        );
    }
}
