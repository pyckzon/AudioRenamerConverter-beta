package com.casl.mp3converter.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Access to the bundled audible watermark asset (requisito 2.2).
 * The asset travels inside the module runtime, but ffmpeg needs a real file on disk:
 * this helper extracts it exactly once to a temp file.
 */
public final class WatermarkAsset {

    private static volatile Path cachedExtractedFile;

    private WatermarkAsset() {
    }

    /** Duration of the bundled asset in seconds (for tests/UI info); -1 unknown. */
    public static double durationHint() {
        return 1.56; // kept in sync with the bundled asset (voice clip ~1.5 s)
    }

    /**
     * Extracts the bundled watermark WAV to a temp file so ffmpeg can read it.
     * Returns {@code null} on failure: callers must degrade gracefully (no watermark).
     */
    public static Path extractToTempFile() {
        Path cached = cachedExtractedFile;
        if (cached != null && Files.exists(cached)) {
            return cached;
        }
        synchronized (WatermarkAsset.class) {
            if (cachedExtractedFile != null && Files.exists(cachedExtractedFile)) {
                return cachedExtractedFile;
            }
            try (var in = WatermarkAsset.class
                    .getResourceAsStream("/audio/watermark-mp3converter.wav")) {
                if (in == null) {
                    return null;
                }
                Path temp = Files.createTempFile("mp3converter-wm-", ".wav");
                temp.toFile().deleteOnExit();
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                cachedExtractedFile = temp;
                return temp;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }
}
