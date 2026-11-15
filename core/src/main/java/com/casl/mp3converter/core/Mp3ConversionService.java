package com.casl.mp3converter.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(Mp3ConversionService.class.getName());

    /** Human readable list of extensions this service can convert. */
    public static final String SUPPORTED_EXTENSIONS_TEXT = "WAV, AIFF, FLAC, M4A, OGG, WMA, OPUS";

    private static final List<String> SUPPORTED_EXTENSIONS =
            List.of("wav", "aif", "aiff", "flac", "m4a", "ogg", "wma", "opus");

    /** Per-file timeout so a broken source file can not hang the whole process forever. */
    private static final long FILE_TIMEOUT_MINUTES = 15;

    // --- freemium configuration (Plan fase 2) ----------------------------------

    /** Encoding quality policy; the default keeps the historical v1.0.0 VBR quality. */
    private final QualityPolicy qualityPolicy;

    /** Optional audible watermark asset; non-null forces both-prepend+append insertion. */
    private final Path watermarkAsset;

    /** Extensions that this tier can NOT convert (e.g. opus in the free tier). */
    private final Set<String> blockedExtensions;

    public Mp3ConversionService() {
        this(QualityPolicy.full(), null, Set.of());
    }

    public Mp3ConversionService(QualityPolicy qualityPolicy) {
        this(qualityPolicy, null, Set.of());
    }

    /** Full freemium constructor: fixed/limited quality, watermark and blocked extensions. */
    public Mp3ConversionService(QualityPolicy qualityPolicy, Path watermarkAsset, Set<String> blockedExtensions) {
        this.qualityPolicy = java.util.Objects.requireNonNull(qualityPolicy, "qualityPolicy");
        this.watermarkAsset = watermarkAsset;
        this.blockedExtensions = Set.copyOf(java.util.Objects.requireNonNullElse(blockedExtensions, Set.<String>of()));
    }

    /**
     * The planned free tier of app-v2: 92 kbps CBR, audible "mp3Converter" watermark
     * at the start and end of every file, .opus blocked (requires the Pro version).
     *
     * @param watermarkAsset extracted watermark audio file (.wav) beside/on disk.
     */
    public static Mp3ConversionService freeTier(Path watermarkAsset) {
        return new Mp3ConversionService(QualityPolicy.freeTier92(), watermarkAsset, Set.of("opus"));
    }

    /** Result of one conversion run. */
    public record ConversionSummary(int converted, int skipped, List<String> skippedFiles) {
        public int totalFiles() {
            return converted + skipped;
        }

        public boolean noFilesFound() {
            return totalFiles() == 0;
        }
    }

    /** Called on the background thread for every processed file. */
    public interface ProgressListener {
        void onProgress(int processed, int total, String fileName);
    }

    /** A known, expected failure the UI should show as a friendly message. */
    public static class ConversionServiceException extends RuntimeException {
        public ConversionServiceException(String message) {
            super(message);
        }
    }

    /**
     * Converts every supported audio file in {@code sourceDir} into an MP3 in {@code destDir}.
     * Never expected to throw: file-level problems are counted as "skipped" and reported.
     */
    public ConversionSummary convert(Path sourceDir, Path destDir, ProgressListener listener)
            throws IOException, InterruptedException {
        ensureFfmpegAvailable();

        List<Path> audioFiles = listAudioFiles(sourceDir);
        if (audioFiles.isEmpty()) {
            return new ConversionSummary(0, 0, List.of());
        }

        List<String> skippedFiles = new ArrayList<>();
        int converted = 0;
        int skipped = 0;
        // Names already generated in THIS run: two sources with the same name (recursive
        // scan) must not collide; outputs from previous runs are replaced instead.
        Set<Path> outputsInRun = new HashSet<>();

        for (int i = 0; i < audioFiles.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Conversión cancelada por el usuario.");
            }
            Path audioFile = audioFiles.get(i);
            listener.onProgress(i + 1, audioFiles.size(), audioFile.getFileName().toString());

            // Tier gate: blocked extensions are listed and reported, ffmpeg NEVER runs for them.
            String blockedReason = blockedExtensionReason(audioFile);
            String result = blockedReason != null ? blockedReason : convertSingleFile(audioFile, destDir, outputsInRun);
            if (result == null) {
                converted++;
            } else {
                skipped++;
                skippedFiles.add(audioFile.getFileName() + ": " + result);
                LOGGER.log(java.util.logging.Level.WARNING,
                        "Archivo omitido {0}: {1}", new Object[]{audioFile, result});
            }
        }
        return new ConversionSummary(converted, skipped, List.copyOf(skippedFiles));
    }

    /** Tier rule: files with blocked extensions stay after the scan and never reach ffmpeg. */
    String blockedExtensionReason(Path audioFile) {
        if (blockedExtensions.isEmpty()) {
            return null;
        }
        int dot = audioFile.getFileName().toString().lastIndexOf('.');
        if (dot < 0 || dot == audioFile.getFileName().toString().length() - 1) {
            return null;
        }
        String extension = audioFile.getFileName().toString().substring(dot + 1).toLowerCase(Locale.ROOT);
        return blockedExtensions.contains(extension)
                ? "requiere la versión Pro: los archivos ." + extension
                  + " no se pueden convertir en la versión gratuita."
                : null;
    }

    /**
     * Converts one file without run-scoped collision tracking (single file, no batch).
     *
     * @return {@code null} on success, or a human readable reason when the file was skipped.
     */
    String convertSingleFile(Path audioFile, Path destDir) throws IOException {
        return convertSingleFile(audioFile, destDir, new HashSet<>());
    }

    /**
     * Converts one file.
     *
     * @param outputsInRun output names already produced in the same conversion batch.
     * @return {@code null} on success, or a human readable reason when the file was skipped.
     */
    String convertSingleFile(Path audioFile, Path destDir, Set<Path> outputsInRun) throws IOException {
        // resolveOutputName returns the base name (no extension): the output is always .mp3.
        String outputName = resolveOutputName(audioFile) + ".mp3";
        Path outputFile = uniqueOutputPath(destDir, outputName, audioFile, outputsInRun);

        // If the target file is the same as the source (mp3 in and mp3 out), avoid destroying data.
        if (Files.exists(outputFile) && isSameFileOrReadOnly(audioFile, outputFile)) {
            return "el archivo de salida ya existe y es el mismo que el de origen.";
        }
        outputsInRun.add(outputFile.toAbsolutePath());

        // The quality policy injects the encoding args: no free-tier rule may be bypassed here.
        List<String> command = buildCommand(
                audioFile.toAbsolutePath(), outputFile.toAbsolutePath());

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

    /**
     * Pure builder of the ffmpeg command (unit-testable without spawning processes):
     * {@code ffmpeg -hide_banner -nostdin -loglevel error -y -i input -codec:a libmp3lame
     * <policy args> output}. Note "-y" keeps overwrite; the target is validated before.
     */
    public static List<String> buildFfmpegCommand(Path input, Path output, QualityPolicy policy) {
        java.util.Objects.requireNonNull(policy, "policy");
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg",
                "-hide_banner",
                "-nostdin",
                "-loglevel", "error",
                "-y",                          // overwrite silently; we validated the target
                "-i", input.toString(),
                "-codec:a", "libmp3lame"
        ));
        command.addAll(policy.encodingArgs());
        command.add(output.toString());
        return List.copyOf(command);
    }

    /** Command of this service instance: free tier inserts the watermark; full keeps plain. */
    private List<String> buildCommand(Path input, Path output) {
        return watermarkAsset == null
                ? buildFfmpegCommand(input, output, qualityPolicy)
                : buildFfmpegCommandWithWatermark(input, output, qualityPolicy, watermarkAsset);
    }

    /**
     * Freemium command (fase 2.2): audible watermark inserted at the START and END of the
     * result. One single ffmpeg pass: the source and the watermark asset (normalized mono/
     * stereo WAV) are merged by filter_complex concat, with fade in/out on the asset so the
     * audio does not pop in/out. Encoding args of the policy stay mandatory: free tier is
     * ALWAYS "-b:a 92k" even inside the watermark pipeline.
     */
    public static List<String> buildFfmpegCommandWithWatermark(Path input, Path output,
                                                               QualityPolicy policy, Path watermarkAsset) {
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(watermarkAsset, "watermarkAsset");

        // Asset must not click on/off: 250 ms triangular fades on both ends.
        String filter = "[0:a]aformat=sample_fmts=s16:sample_rates=44100:channel_layouts=stereo[s0];"
                + "[1:a]aformat=sample_fmts=s16:sample_rates=44100:channel_layouts=stereo,"
                + "afade=t=in:d=0.25:curve=tri,areverse,afade=t=in:d=0.25:curve=tri,areverse[w];"
                + "[s0][w]concat=n=2:v=0:a=1[c1];"
                + "[c1][w]concat=n=2:v=0:a=1[out]";

        List<String> command = new ArrayList<>(List.of(
                "ffmpeg",
                "-hide_banner",
                "-nostdin",
                "-loglevel", "error",
                "-y",                          // overwrite silently; we validated the target
                "-i", input.toString(),
                "-i", watermarkAsset.toString(),
                "-filter_complex", filter,
                "-map", "[out]",
                "-codec:a", "libmp3lame"
        ));
        command.addAll(policy.encodingArgs());
        command.add(output.toString());
        return List.copyOf(command);
    }

    // --- Output naming: "<artist> - <title>" ------------------------------------------

    /**
     * Output file name for the resulting MP3, without extension:
     * {@code "<artista> - <título>"} where the artist comes from the source metadata
     * (ffprobe, format or stream tags) and the title is the original file name minus
     * its trailing "(xxx)" portion (e.g. "cancion (152kbit_Opus).opus" → "cancion").
     * <p>
     * Fallback: no ffprobe or no artist tag → the historical simple name
     * (file name minus extension, parenthetical still removed).
     */
    String resolveOutputName(Path audioFile) throws IOException {
        String base = stripExtension(audioFile.getFileName().toString());
        String title = stripTrailingParenthetical(base);
        String artist = cleanArtist(readArtist(audioFile));
        if (artist.isBlank()) {
            return sanitizeWindowsFileName(title.isBlank() ? base : title);
        }
        return sanitizeWindowsFileName(title.isBlank() ? base : artist + " - " + title);
    }

    boolean isFfprobeAvailable() {
        if (ffprobePresence == null) {
            ffprobePresence = probeBinaryAvailable("ffprobe");
        }
        return ffprobePresence;
    }

    /** Cached once ("ffprobe -version"); the environment does not change mid-run. */
    private Boolean ffprobePresence;

    boolean probeBinaryAvailable(String binary) {
        try {
            Process probe = new ProcessBuilder(binary, "-version")
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

    /** Matches ffprobe plain output lines of the shape "TAG:key=value". */
    private static final java.util.regex.Pattern TAG_LINE =
            java.util.regex.Pattern.compile("TAG:([^=]+)=(.*)");

    /** Per-file max wait for the metadata probe so a locked file can not stall the run. */
    private static final long METADATA_TIMEOUT_SECONDS = 5;

    /**
     * Reads the artist tag of the file (format and stream tags; some containers like
     * OGG/Opus store tags in the stream). Empty string when ffprobe is missing or the
     * tag does not exist. Never throws for tagging problems.
     */
    String readArtist(Path audioFile) {
        if (!isFfprobeAvailable()) {
            return "";
        }
        List<String> command = List.of(
                "ffprobe",
                "-v", "quiet",
                "-show_entries", "format_tags:stream_tags",
                "-of", "default=noprint_wrappers=1",
                audioFile.toAbsolutePath().toString());
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            String output = new String(process.getInputStream().readAllBytes());
            String artist = "";
            String albumArtist = "";
            for (String line : output.split("\\R")) {
                var matcher = TAG_LINE.matcher(line.strip());
                if (!matcher.matches()) {
                    continue;
                }
                String key = matcher.group(1).toLowerCase(Locale.ROOT);
                String value = cleanArtist(matcher.group(2));
                if (key.equals("artist")) {
                    if (!value.isBlank()) {
                        return value;
                    }
                } else if (key.equals("album_artist") || key.equals("albumartist")) {
                    if (albumArtist.isEmpty() && !value.isEmpty()) {
                        albumArtist = value;
                    }
                }
            }
            return albumArtist;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    /** Removes a trailing " - Topic" (YouTube Topic exports) and stray control chars. */
    String cleanArtist(String artist) {
        if (artist == null || artist.isBlank()) {
            return "";
        }
        return artist.replace('\u0000', ' ')
                .replaceAll("(?i)\\s*-\\s*Topic\\s*$", "")
                .trim();
    }

    /** Strips ONE trailing "(...)" group: "Digital Love (152kbit_Opus)" → "Digital Love". */
    String stripTrailingParenthetical(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
    }

    /** Makes any composed name safe to use as a Windows file name. */
    String sanitizeWindowsFileName(String name) {
        String sanitized = name == null ? "" :
                name.replaceAll("[<>:\"/\\\\|?*]", "_")
                        .replaceAll("\\p{Cntrl}", "_")
                        .trim();
        // Windows does not allow names ending with a space or period.
        sanitized = sanitized.replaceAll("[ .]+$", "");
        // Windows reserved device names.
        if (sanitized.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")) {
            sanitized = "_" + sanitized;
        }
        if (sanitized.isBlank()) {
            return "audio";
        }
        // Keep room for the .mp3 extension and Windows path length limits.
        return sanitized.length() > 200 ? sanitized.substring(0, 200).trim() : sanitized;
    }

    private List<Path> listAudioFiles(Path sourceDir) throws IOException {
        List<Path> files = new ArrayList<>();
        // Recursive walk: scans the source folder and every subfolder for audio files.
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> isSupported(p.getFileName().toString()))
                  .sorted(Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT)))
                  .forEach(files::add);
        }
        return files;
    }

    boolean isSupported(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    /**
     * Resolves the output path for {@code outputName} inside {@code destDir}.
     * Collisions only apply to names produced in the same run ({@code outputsInRun}):
     * files left by previous runs are overwritten (same behavior as v1.0.0), while two
     * sources with the same name in one run get "nombre (2).mp3", "nombre (3).mp3", etc.
     */
    Path uniqueOutputPath(Path destDir, String outputName, Path source, Set<Path> outputsInRun) throws IOException {
        Path candidate = destDir.resolve(outputName);
        int counter = 2;
        int dot = outputName.lastIndexOf('.');
        while (outputsInRun.contains(candidate.toAbsolutePath())) {
            candidate = destDir.resolve(outputName.substring(0, dot) + " (" + counter++ + ")" + outputName.substring(dot));
        }
        return candidate;
    }

    private boolean isSameFileOrReadOnly(Path source, Path target) throws IOException {
        try {
            return Files.isSameFile(source, target);
        } catch (IOException e) {
            return false;
        }
    }

    private void ensureFfmpegAvailable() {
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

    /** utilidad pública (app2): ffmpeg presente en el PATH del sistema. */
    public boolean isFfmpegAvailable() {
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

    String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        // Dot-prefixed names (like ".gitignore") have no extension; keeping the dot
        // would strip their whole name and generate(".mp3") outputs.
        return dot <= 0 ? fileName : fileName.substring(0, dot);
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
