package com.casl.audiotools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class Main {

    // Common audio formats handled by FFmpeg.
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "mp4", "aac", "flac", "wav", "wave",
            "ogg", "oga", "opus", "wma", "aiff", "aif", "alac",
            "ape", "mka", "ac3", "eac3", "dts", "amr", "webm"
    );

    private static final Set<String> LOSSLESS_EXTENSIONS = Set.of(
            "flac", "wav", "wave", "alac", "ape", "aiff", "aif"
    );

    private static final ObjectMapper JSON = new ObjectMapper();

    /*
     * FFmpeg is CPU intensive.
     *
     * We intentionally do NOT use all available CPU cores.
     * A small bounded pool is generally better because every worker
     * can also spawn an external FFmpeg process.
     */
    private static final int WORKERS = calculateWorkerCount();

    private static int calculateWorkerCount() {
        int processors = Runtime.getRuntime().availableProcessors();

        // For a normal desktop:
        // 4 workers is usually a good starting point.
        //
        // For CPUs with fewer cores, scale down.
        return Math.max(1, Math.min(4, processors / 2));
    }

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);

        System.out.println("==============================================");
        System.out.println(" Audio Renamer + MP3 Converter");
        System.out.println("==============================================");
        System.out.println();

        Path source = askDirectory(scanner, "Carpeta ORIGEN: ");
        Path destination = askDestination(scanner, "Carpeta DESTINO: ");

        System.out.println();
        System.out.println("Origen : " + source);
        System.out.println("Destino: " + destination);
        System.out.println("Workers: " + WORKERS);
        System.out.println();

        try {
            Files.createDirectories(destination);

            Path ffmpeg = findExecutable("ffmpeg");
            Path ffprobe = findExecutable("ffprobe");

            System.out.println("FFmpeg : " + ffmpeg);
            System.out.println("FFprobe: " + ffprobe);
            System.out.println();

            List<Path> files;

            try (Stream<Path> stream = Files.walk(source)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(Main::isAudioFile)
                        .sorted()
                        .toList();
            }

            System.out.println("Archivos de audio encontrados: " + files.size());
            System.out.println();

            ProcessingSummary summary =
                    processFiles(files, source, destination, ffmpeg, ffprobe);

            printSummary(summary, destination);

        } catch (Exception e) {

            System.err.println();
            System.err.println("ERROR GENERAL: " + e.getMessage());

        } finally {

            System.out.println();
            System.out.println("Presiona ENTER para salir...");
            scanner.nextLine();
        }
    }

    /**
     * Processes all files using a bounded ExecutorService.
     *
     * CompletionService allows us to consume results as soon as
     * each worker finishes instead of waiting for files in submission order.
     */
    private static ProcessingSummary processFiles(
            List<Path> files,
            Path source,
            Path destination,
            Path ffmpeg,
            Path ffprobe
    ) throws InterruptedException {

        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);

        CompletionService<ProcessingResult> completionService =
                new ExecutorCompletionService<>(executor);

        /*
         * This registry is shared between workers.
         *
         * It guarantees that two workers cannot reserve the same
         * destination filename at the same time.
         */
        OutputNameRegistry outputRegistry =
                new OutputNameRegistry();

        int submitted = 0;

        try {

            for (Path input : files) {

                completionService.submit(() ->
                        processSingleFile(
                                input,
                                source,
                                destination,
                                ffmpeg,
                                ffprobe,
                                outputRegistry
                        )
                );

                submitted++;
            }

            AtomicInteger ok = new AtomicInteger();
            AtomicInteger skipped = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            List<String> skippedFiles =
                    Collections.synchronizedList(new ArrayList<>());

            /*
             * Results are consumed as workers finish.
             */
            for (int i = 0; i < submitted; i++) {

                Future<ProcessingResult> future =
                        completionService.take();

                try {

                    ProcessingResult result = future.get();

                    switch (result.status()) {

                        case SUCCESS -> {
                            ok.incrementAndGet();

                            System.out.println(
                                    "[OK] " +
                                            result.input().getFileName() +
                                            " -> " +
                                            result.output()
                            );
                        }

                        case SKIPPED -> {
                            skipped.incrementAndGet();

                            skippedFiles.add(
                                    result.input().getFileName().toString()
                            );

                            System.out.println(
                                    "[OMITIDO] " +
                                            result.input().getFileName() +
                                            " -> " +
                                            result.message()
                            );
                        }

                        case FAILED -> {
                            failed.incrementAndGet();

                            System.err.println(
                                    "[ERROR] " +
                                            result.input().getFileName() +
                                            " -> " +
                                            result.message()
                            );
                        }
                    }

                } catch (ExecutionException e) {

                    failed.incrementAndGet();

                    Throwable cause = e.getCause();

                    System.err.println(
                            "[ERROR] Worker failure: " +
                                    (cause == null
                                            ? e.getMessage()
                                            : cause.getMessage())
                    );
                }
            }

            return new ProcessingSummary(
                    ok.get(),
                    skipped.get(),
                    failed.get(),
                    List.copyOf(skippedFiles)
            );

        } finally {

            /*
             * Always shut down the executor.
             */
            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {

                executor.shutdownNow();

                if (!executor.awaitTermination(
                        30,
                        TimeUnit.SECONDS
                )) {

                    System.err.println(
                            "No fue posible detener completamente "
                                    + "el ExecutorService."
                    );
                }
            }
        }
    }

    /**
     * Processes exactly one file.
     *
     * Everything inside this method is local to the worker,
     * except the thread-safe OutputNameRegistry.
     */
    private static ProcessingResult processSingleFile(
            Path input,
            Path source,
            Path destination,
            Path ffmpeg,
            Path ffprobe,
            OutputNameRegistry outputRegistry
    ) {

        Path temporaryOutput = null;

        try {

            System.out.println(
                    "[" + Thread.currentThread().getName() + "] " +
                            "Procesando: " + input
            );

            /*
             * One ffprobe call returns:
             *
             * - format tags
             * - stream tags
             * - stream bitrate
             */
            ProbeResult probe = probeFile(ffprobe, input);

            Metadata metadata = probe.metadata();

            if (metadata.artist().isBlank()
                    || metadata.title().isBlank()) {

                return ProcessingResult.skipped(
                        input,
                        "falta artist o title en los metadatos."
                );
            }

            String artist = cleanArtist(metadata.artist());
            String title = cleanTitle(metadata.title());

            if (artist.isBlank() || title.isBlank()) {

                return ProcessingResult.skipped(
                        input,
                        "artist/title quedó vacío después de limpiar."
                );
            }

            int sourceBitrate = probe.bitrateKbps();

            int targetBitrate =
                    selectTargetBitrate(
                            input,
                            sourceBitrate
                    );

            String baseName =
                    sanitizeWindowsFileName(
                            artist + " - " + title
                    );

            /*
             * Preserve source folder structure.
             */
            Path relativeParent =
                    source.relativize(input.getParent());

            Path outputDirectory =
                    destination.resolve(relativeParent);

            Files.createDirectories(outputDirectory);

            /*
             * Thread-safe reservation.
             */
            Path output =
                    outputRegistry.reserve(
                            outputDirectory,
                            baseName
                    );

            System.out.printf(
                    "[%s] Nombre: %s%n",
                    Thread.currentThread().getName(),
                    destination.relativize(output)
            );

            System.out.printf(
                    "[%s] Bitrate origen: %s kbps | " +
                            "Bitrate destino: %d kbps%n",
                    Thread.currentThread().getName(),
                    sourceBitrate > 0
                            ? sourceBitrate
                            : "desconocido",
                    targetBitrate
            );

            /*
             * FFmpeg writes to a temporary .part.mp3 file.
             *
             * The final output does not exist until conversion
             * completed successfully.
             */
            temporaryOutput =
                    output.resolveSibling(
                            output.getFileName().toString()
                                    .replace(
                                            ".mp3",
                                            ".part.mp3"
                                    )
                    );

            /*
             * Extremely unlikely, but if a stale temporary file
             * exists, remove it.
             */
            Files.deleteIfExists(temporaryOutput);

            convertToMp3(
                    ffmpeg,
                    input,
                    temporaryOutput,
                    targetBitrate
            );

            /*
             * Move completed file to final destination.
             */
            moveCompletedFile(
                    temporaryOutput,
                    output
            );

            temporaryOutput = null;

            return ProcessingResult.success(
                    input,
                    output
            );

        } catch (Exception e) {

            /*
             * Never leave a partial MP3 behind.
             */
            if (temporaryOutput != null) {

                try {
                    Files.deleteIfExists(temporaryOutput);
                } catch (IOException ignored) {
                    // Nothing else to do here.
                }
            }

            return ProcessingResult.failed(
                    input,
                    e.getMessage() == null
                            ? e.getClass().getSimpleName()
                            : e.getMessage()
            );
        }
    }

    /**
     * One FFprobe invocation per file.
     *
     * Returns both metadata and bitrate.
     *
     * Stream tags are explicitly checked because this is important
     * for Opus/Ogg files.
     */
    private static ProbeResult probeFile(
            Path ffprobe,
            Path input
    ) throws Exception {

        List<String> command = List.of(
                ffprobe.toString(),

                "-v", "quiet",

                "-print_format", "json",

                "-show_entries",
                "format_tags:stream_tags:stream=bit_rate",

                "-select_streams", "a:0",

                input.toString()
        );

        ProcessResult result = execute(command);

        if (result.exitCode() != 0) {

            throw new IOException(
                    "ffprobe no pudo leer los metadatos: " +
                            (result.output().isBlank()
                                    ? "sin detalle"
                                    : result.output().trim())
            );
        }

        JsonNode root =
                JSON.readTree(result.output());

        /*
         * 1. Format tags.
         */
        JsonNode formatTags =
                root.path("format").path("tags");

        /*
         * 2. Stream tags.
         *
         * Important for Opus/Ogg.
         */
        JsonNode streamTags =
                JsonNodeFactory.instance.objectNode();

        JsonNode streams =
                root.path("streams");

        JsonNode audioStream =
                JsonNodeFactory.instance.objectNode();

        if (streams.isArray()
                && !streams.isEmpty()) {

            audioStream = streams.get(0);
            streamTags = audioStream.path("tags");
        }

        String artist = firstNonBlank(
                text(formatTags, "artist"),
                text(formatTags, "ARTIST"),
                text(formatTags, "album_artist"),
                text(formatTags, "ALBUMARTIST"),

                text(streamTags, "artist"),
                text(streamTags, "ARTIST"),
                text(streamTags, "album_artist"),
                text(streamTags, "ALBUMARTIST")
        );

        String title = firstNonBlank(
                text(formatTags, "title"),
                text(formatTags, "TITLE"),

                text(streamTags, "title"),
                text(streamTags, "TITLE")
        );

        /*
         * Fallback to filename.
         */
        if (artist.isBlank()
                || title.isBlank()) {

            Metadata fromName =
                    parseFromFileName(
                            input.getFileName().toString()
                    );

            if (artist.isBlank()) {
                artist = fromName.artist();
            }

            if (title.isBlank()) {
                title = fromName.title();
            }
        }

        int bitrate =
                parseBitrate(
                        audioStream.path("bit_rate")
                );

        return new ProbeResult(
                new Metadata(artist, title),
                bitrate
        );
    }

    private static int parseBitrate(JsonNode node) {

        if (node == null
                || node.isMissingNode()
                || node.isNull()) {

            return -1;
        }

        String value =
                node.asText("").trim();

        if (value.isBlank()
                || value.equalsIgnoreCase("N/A")) {

            return -1;
        }

        try {

            long bitsPerSecond =
                    Long.parseLong(value);

            return (int) Math.round(
                    bitsPerSecond / 1000.0
            );

        } catch (NumberFormatException e) {

            return -1;
        }
    }

    private static void moveCompletedFile(
            Path temporary,
            Path output
    ) throws IOException {

        /*
         * ATOMIC_MOVE is preferred when supported.
         *
         * If the filesystem does not support it,
         * fall back to a normal move.
         */
        try {

            Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE
            );

        } catch (AtomicMoveNotSupportedException e) {

            Files.move(
                    temporary,
                    output
            );
        }
    }

    private static void convertToMp3(
            Path ffmpeg,
            Path input,
            Path output,
            int targetBitrate
    ) throws Exception {

        List<String> command = List.of(
                ffmpeg.toString(),

                "-hide_banner",
                "-loglevel", "error",

                "-y",

                "-i", input.toString(),

                /*
                 * Copy metadata from source.
                 */
                "-map_metadata", "0",

                /*
                 * Audio only.
                 */
                "-vn",

                "-codec:a", "libmp3lame",

                "-b:a",
                targetBitrate + "k",

                output.toString()
        );

        ProcessResult result =
                execute(command);

        if (result.exitCode() != 0) {

            throw new IOException(
                    "FFmpeg falló: " +
                            (result.output().isBlank()
                                    ? "sin detalle"
                                    : result.output().trim())
            );
        }
    }

    /**
     * Executes an external process.
     *
     * stdout and stderr are intentionally merged.
     *
     * This avoids the classic deadlock where Java reads
     * stdout completely and only afterwards reads stderr,
     * while the child process is blocked because stderr's
     * OS buffer is full.
     */
    private static ProcessResult execute(
            List<String> command
    ) throws IOException, InterruptedException {

        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();

        String output;

        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {

            output = reader.lines()
                    .collect(java.util.stream.Collectors.joining(
                            System.lineSeparator()
                    ));
        }

        boolean finished =
                process.waitFor(
                        30,
                        TimeUnit.MINUTES
                );

        if (!finished) {

            process.destroyForcibly();

            throw new IOException(
                    "El proceso excedió el tiempo máximo " +
                            "de 30 minutos."
            );
        }

        return new ProcessResult(
                process.exitValue(),
                output
        );
    }

    /**
     * Thread-safe output filename reservation.
     *
     * Important:
     *
     * Files.exists() alone is NOT enough when multiple
     * workers are processing files simultaneously.
     */
    private static final class OutputNameRegistry {

        private final ConcurrentHashMap<Path, Object> locks =
                new ConcurrentHashMap<>();

        Path reserve(
                Path directory,
                String baseName
        ) {

            /*
             * A lock is associated with the exact destination
             * directory + base filename.
             */
            Path key =
                    directory.resolve(baseName);

            Object lock =
                    locks.computeIfAbsent(
                            key,
                            ignored -> new Object()
                    );

            synchronized (lock) {

                Path candidate =
                        directory.resolve(
                                baseName + ".mp3"
                        );

                int counter = 2;

                while (Files.exists(candidate)) {

                    candidate =
                            directory.resolve(
                                    baseName +
                                            " (" +
                                            counter +
                                            ").mp3"
                            );

                    counter++;
                }

                return candidate;
            }
        }
    }

    private static Path askDirectory(
            Scanner scanner,
            String message
    ) {

        while (true) {

            System.out.print(message);

            String value =
                    scanner.nextLine().trim();

            if (value.isBlank()) {

                System.out.println(
                        "La ruta no puede estar vacía."
                );

                continue;
            }

            Path path =
                    Paths.get(value)
                            .toAbsolutePath()
                            .normalize();

            if (!Files.isDirectory(path)) {

                System.out.println(
                        "La carpeta no existe o no es " +
                                "una carpeta."
                );

                continue;
            }

            return path;
        }
    }

    private static Path askDestination(
            Scanner scanner,
            String message
    ) {

        while (true) {

            System.out.print(message);

            String value =
                    scanner.nextLine().trim();

            if (value.isBlank()) {

                System.out.println(
                        "La ruta no puede estar vacía."
                );

                continue;
            }

            return Paths.get(value)
                    .toAbsolutePath()
                    .normalize();
        }
    }

    private static boolean isAudioFile(Path path) {

        String ext =
                extension(
                        path.getFileName().toString()
                );

        return AUDIO_EXTENSIONS.contains(ext);
    }

    private static String extension(
            String fileName
    ) {

        int dot =
                fileName.lastIndexOf('.');

        if (dot < 0
                || dot == fileName.length() - 1) {

            return "";
        }

        return fileName
                .substring(dot + 1)
                .toLowerCase(Locale.ROOT);
    }

    private static String text(
            JsonNode node,
            String field
    ) {

        if (node == null
                || node.isMissingNode()) {

            return "";
        }

        return node.path(field)
                .asText("")
                .trim();
    }

    /**
     * Attempts to extract Artist and Title from filename.
     *
     * Supports:
     *
     * Artist - Topic - Title (xxx).ext
     * Artist - Title.ext
     */
    private static Metadata parseFromFileName(
            String fileName
    ) {

        int lastDot =
                fileName.lastIndexOf('.');

        String name =
                (lastDot > 0)
                        ? fileName.substring(0, lastDot)
                        : fileName;

        name =
                name.replaceAll(
                        "\\s*\\([^)]*\\)\\s*$",
                        ""
                ).trim();

        if (name.matches(
                "(?i).+\\s+-\\s+Topic\\s+-\\s+.+"
        )) {

            String[] parts =
                    name.split(
                            "(?i)\\s+-\\s+Topic\\s+-\\s+",
                            2
                    );

            if (parts.length == 2) {

                return new Metadata(
                        parts[0].trim(),
                        parts[1].trim()
                );
            }
        }

        int sep =
                name.indexOf(" - ");

        if (sep > 0) {

            return new Metadata(
                    name.substring(0, sep).trim(),
                    name.substring(sep + 3).trim()
            );
        }

        return new Metadata("", "");
    }

    private static int selectTargetBitrate(
            Path input,
            int sourceBitrate
    ) {

        String ext =
                extension(
                        input.getFileName().toString()
                );

        /*
         * Lossless -> 320 kbps.
         */
        if (LOSSLESS_EXTENSIONS.contains(ext)) {
            return 320;
        }

        /*
         * Lossy:
         *
         * <= 192 -> 192
         * > 192  -> 320
         * unknown -> 192
         */
        return sourceBitrate > 192
                ? 320
                : 192;
    }

    private static Path findExecutable(
            String executable
    ) {

        String[] candidates =
                System.getProperty("os.name")
                        .toLowerCase(Locale.ROOT)
                        .contains("win")
                        ? new String[]{
                        executable + ".exe",
                        executable
                }
                        : new String[]{
                        executable
                };

        for (String candidate : candidates) {

            try {

                Process p =
                        new ProcessBuilder(
                                candidate,
                                "-version"
                        )
                                .redirectErrorStream(true)
                                .start();

                if (p.waitFor(
                        10,
                        TimeUnit.SECONDS
                )) {

                    return Paths.get(candidate);
                }

                p.destroyForcibly();

            } catch (Exception ignored) {
                // Try next candidate.
            }
        }

        throw new IllegalStateException(
                "No se encontró " +
                        executable +
                        ". Instala FFmpeg y asegúrate " +
                        "de que ffmpeg/ffprobe estén en " +
                        "el PATH."
        );
    }

    private static String cleanArtist(
            String artist
    ) {

        return artist
                .replace('\u0000', ' ')
                .replaceAll(
                        "(?i)\\s*-\\s*Topic\\s*$",
                        ""
                )
                .trim();
    }

    private static String cleanTitle(
            String title
    ) {

        return title
                .replace('\u0000', ' ')
                .trim();
    }

    private static String sanitizeWindowsFileName(
            String name
    ) {

        String sanitized =
                name
                        .replaceAll(
                                "[<>:\"/\\\\|?*]",
                                "_"
                        )
                        .replaceAll(
                                "\\p{Cntrl}",
                                "_"
                        )
                        .trim();

        /*
         * Windows does not allow names ending
         * with a space or period.
         */
        sanitized =
                sanitized.replaceAll(
                        "[ .]+$",
                        ""
                );

        /*
         * Windows reserved device names.
         */
        if (sanitized.matches(
                "(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$"
        )) {

            sanitized =
                    "_" + sanitized;
        }

        if (sanitized.isBlank()) {
            return "audio";
        }

        /*
         * Keep enough room for .mp3 and path limits.
         */
        if (sanitized.length() > 200) {

            sanitized =
                    sanitized.substring(0, 200)
                            .trim();
        }

        return sanitized;
    }

    private static String firstNonBlank(
            String... values
    ) {

        for (String value : values) {

            if (value != null
                    && !value.isBlank()) {

                return value.trim();
            }
        }

        return "";
    }

    private static void printSummary(
            ProcessingSummary summary,
            Path destination
    ) {

        System.out.println();
        System.out.println(
                "=============================================="
        );
        System.out.println(
                " PROCESO TERMINADO"
        );
        System.out.println(
                "=============================================="
        );

        System.out.println(
                "Convertidos: " +
                        summary.ok()
        );

        System.out.println(
                "Omitidos   : " +
                        summary.skipped()
        );

        System.out.println(
                "Errores    : " +
                        summary.failed()
        );

        System.out.println(
                "Destino    : " +
                        destination
        );

        if (!summary.skippedFiles().isEmpty()) {

            System.out.println();
            System.out.println(
                    "Archivos omitidos:"
            );

            summary.skippedFiles()
                    .forEach(file ->
                            System.out.println(
                                    " - " + file
                            )
                    );
        }

        System.out.println();
    }

    private enum ProcessingStatus {
        SUCCESS,
        SKIPPED,
        FAILED
    }

    private record Metadata(
            String artist,
            String title
    ) {
    }

    private record ProbeResult(
            Metadata metadata,
            int bitrateKbps
    ) {
    }

    private record ProcessResult(
            int exitCode,
            String output
    ) {
    }

    private record ProcessingResult(
            Path input,
            Path output,
            ProcessingStatus status,
            String message
    ) {

        static ProcessingResult success(
                Path input,
                Path output
        ) {

            return new ProcessingResult(
                    input,
                    output,
                    ProcessingStatus.SUCCESS,
                    ""
            );
        }

        static ProcessingResult skipped(
                Path input,
                String message
        ) {

            return new ProcessingResult(
                    input,
                    null,
                    ProcessingStatus.SKIPPED,
                    message
            );
        }

        static ProcessingResult failed(
                Path input,
                String message
        ) {

            return new ProcessingResult(
                    input,
                    null,
                    ProcessingStatus.FAILED,
                    message
            );
        }
    }

    private record ProcessingSummary(
            int ok,
            int skipped,
            int failed,
            List<String> skippedFiles
    ) {
    }
}
