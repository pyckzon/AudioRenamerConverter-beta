package com.casl.mp3converter.core;

import java.util.List;

/**
 * Encoding quality policy of the conversion service (Plan fase 1).
 * <p>
 * The service never invents ffmpeg arguments by itself: it always asks the policy.
 * This is the seam used by the future freemium build (app-v2) to force
 * {@code 92 kbps} CBR on the free tier while the full build keeps VBR quality.
 */
public interface QualityPolicy {

    /**
     * ffmpeg encoding arguments to insert after {@code (-codec:a libmp3lame)}.
     * e.g. {@code List.of("-q:a", "2")} (VBR) or {@code List.of("-b:a", "92k")} (CBR).
     */
    List<String> encodingArgs();

    /** Decoder/VBR quality: the historical v1.0.0 behavior ("high quality VBR"). */
    static QualityPolicy full() {
        return () -> List.of("-q:a", "2");
    }

    /** Free-tier CBR: a fixed {@code kbps} bitrate, no VBR. Validated: 8..320. */
    static QualityPolicy cbrFixed(int kbps) {
        if (kbps < 8 || kbps > 320) {
            throw new IllegalArgumentException("kbps fuera de rango (8..320): " + kbps);
        }
        return () -> List.of("-b:a", kbps + "k");
    }

    /** The planned free tier of the freemium build: 92 kbps CBR. */
    static QualityPolicy freeTier92() {
        return cbrFixed(92);
    }
}
