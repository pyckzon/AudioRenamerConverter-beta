package com.casl.audiotools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Main {

    // Common audio formats handled by FFmpeg.
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "mp4", "aac", "flac", "wav", "wave",
            "ogg", "oga", "opus", "wma", "aiff", "aif", "alac",
            "ape", "mka", "ac3", "eac3", "dts", "amr", "webm"
    );

    private static final ObjectMapper JSON = new ObjectMapper();

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

            int ok = 0;
            int skipped = 0;
            int failed = 0;
            StringBuilder omitidos = new StringBuilder();

            for (Path input : files) {
                System.out.println("----------------------------------------------");
                System.out.println("Procesando: " + input);

                try {
                    Metadata metadata = readMetadata(ffprobe, input);

                    if (metadata.artist().isBlank() || metadata.title().isBlank()) {
                        System.out.println("OMITIDO: falta artist o title en los metadatos.");
                        omitidos.append(input.getFileName()).append(", ");
                        skipped++;
                        continue;
                    }

                    String artist = cleanArtist(metadata.artist());
                    String title = cleanTitle(metadata.title());

                    if (artist.isBlank() || title.isBlank()) {
                        System.out.println("OMITIDO: artist/title quedó vacío después de limpiar.");
                        omitidos.append(input.getFileName()).append(", ");
                        skipped++;
                        continue;
                    }

                    int sourceBitrate = readBitrateKbps(ffprobe, input);
                    int targetBitrate = selectTargetBitrate(input, sourceBitrate);

                    String baseName = sanitizeWindowsFileName(artist + " - " + title);

                    // Preserve the source folder structure below the destination.
                    Path relativeParent = source.relativize(input.getParent());
                    Path outputDirectory = destination.resolve(relativeParent);
                    Files.createDirectories(outputDirectory);

                    Path output = uniqueOutputPath(outputDirectory, baseName);

                    System.out.printf("Nombre : %s%n", destination.relativize(output));
                    System.out.printf("Bitrate origen: %s kbps | Bitrate destino: %d kbps%n",
                            sourceBitrate > 0 ? sourceBitrate : "desconocido", targetBitrate);

                    convertToMp3(ffmpeg, input, output, targetBitrate);

                    ok++;
                    System.out.println("OK");

                } catch (Exception e) {
                    failed++;
                    System.out.println("ERROR: " + e.getMessage());
                }
            }

            System.out.println();
            System.out.println("==============================================");
            System.out.println(" PROCESO TERMINADO");
            System.out.println("==============================================");
            System.out.println("Convertidos: " + ok);
            System.out.println("Omitidos   : " + skipped);
            System.out.println("Errores    : " + failed);
            System.out.println("Destino    : " + destination);
            System.out.println("Lista de Omitidos: "+ omitidos);
            System.out.println();

        } catch (Exception e) {
            System.err.println("ERROR GENERAL: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("Presiona ENTER para salir...");
        scanner.nextLine();
    }

    private static Path askDirectory(Scanner scanner, String message) {
        while (true) {
            System.out.print(message);
            String value = scanner.nextLine().trim();

            if (value.isBlank()) {
                System.out.println("La ruta no puede estar vacía.");
                continue;
            }

            Path path = Paths.get(value).toAbsolutePath().normalize();

            if (!Files.isDirectory(path)) {
                System.out.println("La carpeta no existe o no es una carpeta.");
                continue;
            }

            return path;
        }
    }

    private static Path askDestination(Scanner scanner, String message) {
        while (true) {
            System.out.print(message);
            String value = scanner.nextLine().trim();

            if (value.isBlank()) {
                System.out.println("La ruta no puede estar vacía.");
                continue;
            }

            return Paths.get(value).toAbsolutePath().normalize();
        }
    }

    private static boolean isAudioFile(Path path) {
        String ext = extension(path.getFileName().toString());
        return AUDIO_EXTENSIONS.contains(ext);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Metadata readMetadata(Path ffprobe, Path input) throws Exception {
        List<String> command = List.of(
                ffprobe.toString(),
                "-v", "quiet",
                "-print_format", "json",
                "-show_entries", "format_tags:stream_tags",
                "-select_streams", "a:0",
                input.toString()
        );

        ProcessResult result = execute(command);

        if (result.exitCode() != 0) {
            throw new IOException(
                    "ffprobe no pudo leer los metadatos: " +
                            (result.stderr().isBlank() ? "sin detalle" : result.stderr().trim())
            );
        }

        JsonNode root = JSON.readTree(result.stdout());

        // 1. Intentar desde format.tags
        JsonNode formatTags = root.path("format").path("tags");

        // 2. Intentar desde streams[0].tags (importante para Opus/Ogg)
        JsonNode streamTags = JsonNodeFactory.instance.objectNode();
        JsonNode streams = root.path("streams");
        if (streams.isArray() && !streams.isEmpty()) {
            streamTags = streams.get(0).path("tags");
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

        // 3. Fallback: intentar sacar artist/title del nombre del archivo
        //    Ejemplo: "Daft Punk - Topic - Digital Love (152kbit_Opus).mp3"
        if (artist.isBlank() || title.isBlank()) {
            Metadata fromName = parseFromFileName(input.getFileName().toString());
            if (artist.isBlank()) artist = fromName.artist();
            if (title.isBlank())  title  = fromName.title();
        }

        return new Metadata(artist, title);
    }
    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return "";
        return node.path(field).asText("").trim();
    }

    /**
     * Intenta extraer Artist y Title desde el nombre del archivo.
     * Soporta patrones comunes de YouTube Topic:
     *   "Artist - Topic - Title (xxxkbit_XXX).ext"
     *   "Artist - Title.ext"
     */
    private static Metadata parseFromFileName(String fileName) {
        // Quitar extensión
        int lastDot = fileName.lastIndexOf('.');
        String name = (lastDot > 0) ? fileName.substring(0, lastDot) : fileName;

        // Quitar cosas entre paréntesis al final: (152kbit_Opus), (Official Audio), etc.
        name = name.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();

        // Caso típico: "Artist - Topic - Title"
        if (name.matches("(?i).+\\s+-\\s+Topic\\s+-\\s+.+")) {
            String[] parts = name.split("(?i)\\s+-\\s+Topic\\s+-\\s+", 2);
            if (parts.length == 2) {
                return new Metadata(parts[0].trim(), parts[1].trim());
            }
        }

        // Caso simple: "Artist - Title"
        int sep = name.indexOf(" - ");
        if (sep > 0) {
            return new Metadata(
                    name.substring(0, sep).trim(),
                    name.substring(sep + 3).trim()
            );
        }

        // No se pudo
        return new Metadata("", "");
    }

    private static int readBitrateKbps(Path ffprobe, Path input) throws Exception {
        List<String> command = List.of(
                ffprobe.toString(),
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=bit_rate",
                "-of", "default=noprint_wrappers=1:nokey=1",
                input.toString()
        );

        ProcessResult result = execute(command);

        if (result.exitCode() != 0) return -1;

        String value = result.stdout().trim();
        if (value.isBlank() || value.equals("N/A")) return -1;

        try {
            long bitsPerSecond = Long.parseLong(value);
            return (int) Math.round(bitsPerSecond / 1000.0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int selectTargetBitrate(Path input, int sourceBitrate) {
        String ext = extension(input.getFileName().toString());

        // Lossless formats have no useful "MP3 bitrate" comparison.
        // They contain more source information than a 192 kbps MP3,
        // so use the higher MP3 target.
        Set<String> lossless = Set.of(
                "flac", "wav", "wave", "alac", "ape", "aiff", "aif"
        );

        if (lossless.contains(ext)) {
            return 320;
        }

        // For lossy formats:
        // <= 192 kbps (or unknown) -> 192 kbps
        // > 192 kbps              -> 320 kbps
        return sourceBitrate > 192 ? 320 : 192;
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

                // Copy metadata from the source into the MP3.
                "-map_metadata", "0",

                // Convert the audio to MP3.
                "-vn",
                "-codec:a", "libmp3lame",
                "-b:a", targetBitrate + "k",

                output.toString()
        );

        ProcessResult result = execute(command);

        if (result.exitCode() != 0) {
            throw new IOException(
                    "FFmpeg falló: " +
                    (result.stderr().isBlank() ? "sin detalle" : result.stderr().trim())
            );
        }
    }

    private static ProcessResult execute(List<String> command)
            throws IOException, InterruptedException {

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();

        String stdout;
        String stderr;

        try (BufferedReader out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedReader err = new BufferedReader(
                     new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {

            stdout = out.lines().collect(Collectors.joining(System.lineSeparator()));
            stderr = err.lines().collect(Collectors.joining(System.lineSeparator()));
        }

        boolean finished = process.waitFor(30, TimeUnit.MINUTES);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("El proceso excedió el tiempo máximo de 30 minutos.");
        }

        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private static Path findExecutable(String executable) {
        String[] candidates = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? new String[]{executable + ".exe", executable}
                : new String[]{executable};

        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(candidate, "-version")
                        .redirectErrorStream(true)
                        .start();

                if (p.waitFor(10, TimeUnit.SECONDS)) {
                    return Paths.get(candidate);
                }

                p.destroyForcibly();
            } catch (Exception ignored) {
            }
        }

        throw new IllegalStateException(
                "No se encontró " + executable +
                ". Instala FFmpeg y asegúrate de que ffmpeg/ffprobe estén en el PATH."
        );
    }

    private static String cleanArtist(String artist) {
        // Removes a trailing " - Topic", case-insensitive.
        return artist
                .replace('\u0000', ' ')
                .replaceAll("(?i)\\s*-\\s*Topic\\s*$", "")
                .trim();
    }

    private static String cleanTitle(String title) {
        return title
                .replace('\u0000', ' ')
                .trim();
    }

    private static String sanitizeWindowsFileName(String name) {
        String sanitized = name
                .replaceAll("[<>:\"/\\\\|?*]", "_")
                .replaceAll("\\p{Cntrl}", "_")
                .trim();

        // Windows does not allow names ending with a space or period.
        sanitized = sanitized.replaceAll("[ .]+$", "");

        // Avoid Windows reserved device names.
        if (sanitized.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")) {
            sanitized = "_" + sanitized;
        }

        if (sanitized.isBlank()) {
            return "audio";
        }

        // Keep enough room for the .mp3 extension and Windows path limits.
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200).trim();
        }

        return sanitized;
    }

    private static Path uniqueOutputPath(Path directory, String baseName) {
        Path candidate = directory.resolve(baseName + ".mp3");
        int counter = 2;

        while (Files.exists(candidate)) {
            candidate = directory.resolve(baseName + " (" + counter + ")" + ".mp3");
            counter++;
        }

        return candidate;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record Metadata(String artist, String title) {}
    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
