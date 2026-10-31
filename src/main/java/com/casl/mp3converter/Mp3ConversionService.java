package com.casl.mp3converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Converts audio files from a source folder into MP3 files inside a destination folder.
 * <p>
 * The actual encoding is delegated to an external {@code ffmpeg} binary, which keeps the
 * application free of heavy audio dependencies. All failures are reported as results/
 * exceptions instead of crashes so the UI can show informative dialogs.
 */
public class Mp3ConversionService {

    /** Human readable list of extensions this service can convert. */
    public static final String SUPPORTED_EXTENSIONS_TEXT = "WAV, AIFF, FLAC, M4A, OGG, WMA";

    private static final List<String> SUPPORTED_EXTENSIONS =
            List.of("wav", "aif", "aiff", "flac", "m4a", "ogg", "wma");

    /** Per-file timeout so a broken source file can not hang the whole process forever. */
    private static final long FILE_TIMEOUT_MINUTES = 15;

    /** Result of one conversion run. */
    record ConversionSummary(int converted, int skipped, List<String> skippedFiles) {
        int totalFiles() {
            return converted + skipped;
        }

        boolean noFilesFound() {
            return totalFiles() == 0;
        }
    }

    /** Called on the background thread for every processed file. */
    interface ProgressListener {
        void onProgress(int processed, int total, String fileName);
    }

    /** A known, expected failure the UI should show as a friendly message. */
    static class ConversionServiceException extends RuntimeException {
        ConversionServiceException(String message) {
            super(message);
        }
    }

    /**
     * Converts every supported audio file in {@code sourceDir} into an MP3 in {@code destDir}.
     * Never expected to throw: file-level problems are counted as "skipped" and reported.
     */
    ConversionSummary convert(Path sourceDir, Path destDir, ProgressListener listener)
            throws IOException, InterruptedException {
        ensureFfmpegAvailable(sourceDir, destDir);

        List<Path> audioFiles = listAudioFiles(sourceDir, destDir);
        if (audioFiles.isEmpty()) {
            return new ConversionSummary(0, 0, List.of());
        }

        List<String> skippedFiles = new ArrayList<>();
        int converted = 0;
        int skipped = 0;

        for (int i = 0; i < audioFiles.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Conversión cancelada por el usuario.");
            }
            Path audioFile = audioFiles.get(i);
            listener.onProgress(i + 1, audioFiles.size(), audioFile.getFileName().toString());

            String result = convertSingleFile(audioFile, destDir);
            if (result == null) {
                converted++;
            } else {
                skipped++;
                skippedFiles.add(audioFile.getFileName() + ": " + result);
            }
        }
        return new ConversionSummary(converted, skipped, List.copyOf(skippedFiles));
    }

    /**
     * Converts one file.
     *
     * @return {@code null} on success, or a human readable reason when the file was skipped.
     */
    private String convertSingleFile(Path audioFile, Path destDir) throws IOException {
        String outputName = stripExtension(audioFile.getFileName().toString()) + ".mp3";
        Path outputFile = destDir.resolve(outputName);

        // If the target file is the same as the source (mp3 in and mp3 out), avoid destroying data.
        if (Files.exists(outputFile) && isSameFileOrReadOnly(audioFile, outputFile)) {
            return "el archivo de salida ya existe y es el mismo que el de origen.";
        }

        List<String> command = List.of(
                "ffmpeg",
                "-hide_banner",
                "-nostdin",
                "-loglevel", "error",
                "-y",                          // overwrite silently; we validated the target
                "-i", audioFile.toAbsolutePath().toString(),
                "-codec:a", "libmp3lame",
                "-q:a", "2",                   // high quality VBR
                outputFile.toAbsolutePath().toString()
        );

        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            Process process = builder.start();

            boolean finished = process.waitFor(FILE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return "tiempo de espera agotado (" + FILE_TIMEOUT_MINUTES + " min).";
            }

            if (process.exitValue() != 0) {
                return "falló la conversión (código " + process.exitValue() + "). "
                       + truncateForMessage(readProcessOutput(process));
            }
            return null;
        } catch (InterruptedException e) {
            // The user cancelled: make sure no orphan ffmpeg processes survive.
            Thread.currentThread().interrupt();
            return "cancelado por el usuario.";
        } catch (RuntimeException e) {
            return "error inesperado: " + e;
        }
    }

    // ----------------------------------------------------------------------

    private List<Path> listAudioFiles(Path sourceDir, Path destDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(sourceDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> isSupported(p.getFileName().toString()))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                  .forEach(files::add);
        }
        return files;
    }

    private boolean isSupported(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    private boolean isSameFileOrReadOnly(Path source, Path target) throws IOException {
        try {
            return Files.isSameFile(source, target);
        } catch (IOException e) {
            return false;
        }
    }

    private void ensureFfmpegAvailable(Path sourceDir, Path destDir) {
        if (isFfmpegAvailable()) {
            return;
        }
        throw new ConversionServiceException(
                "No se encontró 'ffmpeg' en el sistema.\n\n"
                + "Este programa usa ffmpeg para convertir el audio a MP3.\n"
                + "Para solucionarlo:\n"
                + "  1. Descarga ffmpeg de 'ffmpeg.org' o instálalo con 'winget install ffmpeg'.\n"
                + "  2. Asegúrate de que 'ffmpeg' esté en el PATH del sistema.\n"
                + "  3. Reinicia la aplicación e inténtalo de nuevo.");
    }

    boolean isFfmpegAvailable() {
        try {
            Process probe = new ProcessBuilder("ffmpeg", "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return false;
            }
            return probe.exitValue() == 0;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private String readProcessOutput(Process process) throws IOException {
        try (var output = process.getInputStream()) {
            return new String(output.readAllBytes());
        }
    }

    private String truncateForMessage(String text) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) {
            return "";
        }
        return "(ffmpeg: " + (clean.length() > 400 ? clean.substring(0, 400) + "..." : clean) + ")";
    }
}
