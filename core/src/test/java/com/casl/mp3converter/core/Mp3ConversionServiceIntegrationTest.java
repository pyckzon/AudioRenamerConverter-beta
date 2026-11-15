package com.casl.mp3converter.core;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * "Archivo especial" de pruebas de integración: convierten audio de verdad con
 * ffmpeg en carpetas temporales, exactamente igual que hace la aplicación.
 * <p>
 * A difference from the unit tests, these are marked with the "integration" tag
 * and are NOT executed by the default "gradlew test" task; they run with
 * "gradlew integrationTest" (which "check" also triggers). If ffmpeg is not
 * available on the machine the tests are skipped (never failed), so a build on
 * a machine without ffmpeg still stays green.
 */
@Tag("integration")
@DisplayName("Mp3ConversionService - pruebas de integración (ffmpeg real)")
class Mp3ConversionServiceIntegrationTest {

    private final Mp3ConversionService service = new Mp3ConversionService();

    // Runs once per test: skips the test (instead of failing it) when ffmpeg is absent.
    private void requireFfmpeg() {
        assumeTrue(service.isFfmpegAvailable(),
                "ffmpeg no está disponible en esta máquina: se omite la prueba de integración");
    }

    // --- Test data helpers --------------------------------------------------------

    /** Generates a valid 16-bit PCM WAV file with a 440 Hz tone. */
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
        buffer.putInt(16);                       // size of the fmt chunk
        buffer.putShort((short) 1);              // PCM
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * bytesPerSample); // byte rate
        buffer.putShort((short) (channels * bytesPerSample));  // block align
        buffer.putShort((short) bitsPerSample);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataLen);
        for (int i = 0; i < sampleCount; i++) {
            buffer.putShort((short) (7000 * Math.sin(2 * Math.PI * 440 * i / sampleRate)));
        }
        assertTrue(buffer.hasRemaining() == false, "the WAV generator must fill its buffer exactly");

        var file = dir.resolve(fileName);
        return Files.write(file, buffer.array());
    }

    /** Progress listener that records every call in the returned list. */
    private static List<String> listening(Mp3ConversionService.ProgressListener[] holder) {
        List<String> calls = new ArrayList<>();
        holder[0] = (processed, total, fileName) -> calls.add(processed + "/" + total + ":" + fileName);
        return calls;
    }

    // --- Integration tests ---------------------------------------------------------

    @Test
    @DisplayName("convierte los WAV de la carpeta origen en MP3 en la carpeta destino")
    void convierteWav_aMp3(@TempDir Path sourceDir, @TempDir Path destDir) throws Exception {
        requireFfmpeg();
        createWav(sourceDir, "cancion1.wav", 0.5);
        createWav(sourceDir, "cancion2.wav", 0.5);

        Mp3ConversionService.ProgressListener[] holder = new Mp3ConversionService.ProgressListener[1];
        var calls = listening(holder);
        var summary = service.convert(sourceDir, destDir, holder[0]);

        assertAll(
                () -> assertEquals(2, summary.converted()),
                () -> assertEquals(0, summary.skipped(), "no file should be skipped"),
                () -> assertEquals(2, calls.size()),
                () -> assertEquals("1/2:cancion1.wav", calls.get(0), "files are processed in name order"),
                () -> assertEquals("2/2:cancion2.wav", calls.get(1)),
                () -> assertTrue(Files.size(destDir.resolve("cancion1.mp3")) > 1000,
                        "the output mp3 must exist and be non-trivially sized"),
                () -> assertTrue(Files.exists(destDir.resolve("cancion2.mp3")))
        );
    }

    @Test
    @DisplayName("los archivos corruptos no detienen el proceso: quedan como omitidos con motivo")
    void archivoCorrupto_noDetieneYQuedaOmitido(@TempDir Path sourceDir, @TempDir Path destDir) throws Exception {
        requireFfmpeg();

        createWav(sourceDir, "buena.wav", 0.5);
        // Broken file: .wav extension but garbage bytes inside.
        Files.writeString(sourceDir.resolve("rota.wav"), "esto no es un archivo WAV valido",
                StandardCharsets.UTF_8);

        var summary = service.convert(sourceDir, destDir, (p, t, f) -> { });

        assertAll(
                () -> assertEquals(1, summary.converted(), "the valid file must still convert"),
                () -> assertEquals(1, summary.skipped()),
                () -> assertEquals(1, summary.skippedFiles().size()),
                () -> assertTrue(summary.skippedFiles().get(0).startsWith("rota.wav: "),
                        "the omission must report the skipped file"),
                () -> assertTrue(Files.exists(destDir.resolve("buena.mp3"))),
                () -> assertFalse(Files.exists(destDir.resolve("rota.mp3")),
                        "no output must be left behind for a failed conversion")
        );
    }

    @Test
    @DisplayName("los archivos mp3 de origen no se re-converten y los txt se ignoran")
    void soloProcesaAudioConvertible(@TempDir Path sourceDir, @TempDir Path destDir) throws Exception {
        requireFfmpeg();
        createWav(sourceDir, "pista.wav", 0.3);
        Files.writeString(sourceDir.resolve("yaconvertida.mp3"), "mp3 no se reconvierte");
        Files.writeString(sourceDir.resolve("leeme.txt"), "no es audio");

        var summary = service.convert(sourceDir, destDir, (p, t, f) -> { });

        assertAll(
                () -> assertEquals(1, summary.totalFiles(), "only pista.wav must be processed"),
                () -> assertEquals(1, summary.converted()),
                () -> assertTrue(Files.exists(destDir.resolve("pista.mp3")))
        );
    }

    @Test
    @DisplayName("una conversión previa al destino no impide volver a convertir (sobrescritura)")
    void sobreescribeSalidasPrevias(@TempDir Path sourceDir, @TempDir Path destDir) throws Exception {
        requireFfmpeg();
        createWav(sourceDir, "pista.wav", 0.4);
        // Old (fake, tiny) output from an earlier run.
        Files.write(destDir.resolve("pista.mp3"), "salida vieja de prueba".getBytes(StandardCharsets.UTF_8));
        long oldSize = Files.size(destDir.resolve("pista.mp3"));

        var summary = service.convert(sourceDir, destDir, (p, t, f) -> { });

        assertAll(
                () -> assertEquals(1, summary.converted()),
                () -> assertTrue(Files.size(destDir.resolve("pista.mp3")) > oldSize,
                        "the conversion must replace the old output")
        );
    }

    @Test
    @DisplayName("convierte archivos .opus a MP3 (antes se ignoraban silenciosamente)")
    void convierteOpus_aMp3(@TempDir Path sourceDir, @TempDir Path destDir) throws Exception {
        requireFfmpeg();

        // Synthetic opus source: 440 Hz tone encoded with libopus (skip if the
        // encoder is missing in this ffmpeg build - NEVER fail for it).
        Path opus = sourceDir.resolve("tono.opus");
        boolean generated = generateOpus(opus);
        assumeTrue(generated, "libopus no disponible en esta build de ffmpeg: se omite la prueba");
        assumeTrue(Files.size(opus) > 0, "la generacion del opus sintetico fallo: se omite la prueba");

        var summary = service.convert(sourceDir, destDir, (p, t, f) -> { });

        assertAll(
                () -> assertEquals(1, summary.converted()),
                () -> assertEquals(0, summary.skipped()),
                () -> assertTrue(Files.size(destDir.resolve("tono.mp3")) > 1000)
        );
    }

    /** Generates a small valid .opus file with ffmpeg; returns false if the encoder is absent. */
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

    @Test
    @DisplayName("escanea subcarpetas recursivamente y resuelve colisiones de nombre en destino")
    void escaneaSubcarpetas_yResuelveColisiones(@TempDir Path sourceDir, @TempDir Path destDir) throws Exception {
        requireFfmpeg();
        var subA = Files.createDirectories(sourceDir.resolve("album"));
        var subB = Files.createDirectories(sourceDir.resolve("album/mono"));
        createWav(subA, "pista.wav", 0.4);
        createWav(subB, "pista.wav", 0.4);

        var summary = service.convert(sourceDir, destDir, (p, t, f) -> { });

        assertAll(
                () -> assertEquals(2, summary.converted(), "ambas copias de pista.wav se deben convertir"),
                () -> assertEquals(0, summary.skipped()),
                () -> assertTrue(Files.exists(destDir.resolve("pista.mp3"))),
                () -> assertTrue(Files.exists(destDir.resolve("pista (2).mp3")),
                        "la segunda pista homónima debe tener sufijo en vez de pisar el archivo")
        );
    }

    @Test
    @DisplayName("el MP3 resultante se nombra con el artista de los metadatos: '<artista> - <titulo>'")
    void nombraSalida_conArtistaDeMetadatos(@TempDir Path sourceDir, @TempDir Path destDir) throws Exception {
        requireFfmpeg();

        // WAV sintético CON tag de artista escrito por el propio ffmpeg.
        Path tagged = sourceDir.resolve("tema.wav");
        boolean generated = generateWavWithMetadata(tagged, "Artista Prueba");
        assumeTrue(generated, "no se pudo generar el WAV con metadatos: se omite la prueba");

        var summary = service.convert(sourceDir, destDir, (p, t, f) -> { });

        assertAll(
                () -> assertEquals(1, summary.converted()),
                () -> assertTrue(Files.exists(destDir.resolve("Artista Prueba - tema.mp3")),
                        "la salida debe llamarse 'Artista Prueba - tema.mp3' con el artista de los metadatos")
        );
    }

    /** Generates a valid PCM WAV with a 440 Hz tone and an artist tag. Returns false on ffmpeg failure. */
    private static boolean generateWavWithMetadata(Path target, String artist) {
        try {
            var temp = Files.createTempFile("tagged-", ".wav");
            temp.toFile().deleteOnExit();
            Process p = new ProcessBuilder("ffmpeg",
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=0.4",
                    "-metadata", "artist=" + artist,
                    temp.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            if (p.exitValue() != 0 || Files.size(temp) < 1000) {
                return false;
            }
            Files.move(temp, target);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("el free tier inserta el watermark audible: la salida dura fuente + 2x asset")
    void freeTier_aplicaWatermarkAudible(@TempDir Path sourceDir, @TempDir Path destDir) throws Exception {
        requireFfmpeg();
        var wmAsset = extractWatermarkAsset();
        assumeTrue(wmAsset != null, "asset del watermark no disponible: se omite la prueba");

        double sourceSeconds = 1.5;
        createWav(sourceDir, "pista.wav", sourceSeconds);

        var gate = new Mp3ConversionService(QualityPolicy.freeTier92(), wmAsset, Set.of());
        gate.convert(sourceDir, destDir, (p, t, f) -> { });

        Path freeOutput = destDir.resolve("pista.mp3");
        double watermarkSeconds = probeDurationSeconds(wmAsset);
        double freeDuration = probeDurationSeconds(freeOutput);
        double expectedMinimum = sourceSeconds + watermarkSeconds * 2 - 0.5;
        double expectedMaximum = sourceSeconds + watermarkSeconds * 2 + 1.0;

        assertTrue(freeDuration >= expectedMinimum && freeDuration <= expectedMaximum,
                String.format("la salida free debe tener el watermark al inicio y al fin " +
                        "(%.2f ≈ %.2f + 2x%.2f). Real: %.2f", freeDuration, sourceSeconds, watermarkSeconds, freeDuration));
    }

    /** Extracts the watermark wav from the module resources to a temp file (or null). */
    static Path extractWatermarkAsset() {
        try (var in = Mp3ConversionServiceIntegrationTest.class
                .getResourceAsStream("/audio/watermark-mp3converter.wav")) {
            if (in == null) {
                return null;
            }
            Path temp = Files.createTempFile("wm-", ".wav");
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (IOException e) {
            return null;
        }
    }

    /** Seconds of media duration via ffprobe; -1 when unavailable. */
    static double probeDurationSeconds(Path media) {
        try {
            Process p = new ProcessBuilder("ffprobe", "-v", "quiet",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    media.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return Double.parseDouble(new String(p.getInputStream().readAllBytes()).strip());
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    @DisplayName("convertir la carpeta vacía no lanza excepción y reporta cero archivos")
    void carpetaVacia_noFalla(@TempDir Path sourceDir, @TempDir Path destDir) {
        requireFfmpeg();

        var summary = assertDoesNotThrow(() -> service.convert(sourceDir, destDir, (p, t, f) -> { }));

        assertTrue(summary.noFilesFound());
    }
}
