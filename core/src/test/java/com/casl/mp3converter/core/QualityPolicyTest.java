package com.casl.mp3converter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules of the encoding quality policy and the ffmpeg command builder.
 * These are the seams of the future freemium restrictions: the free tier must
 * produce exactly "-b:a 92k" and never a seeded/hidden higher bitrate.
 */
@DisplayName("QualityPolicy - politica de calidad y comando ffmpeg")
class QualityPolicyTest {

    // --- QualityPolicy -------------------------------------------------------

    @Test
    @DisplayName("full() mantiene la calidad VBR historica de la v1.0.0 (-q:a 2)")
    void fullPolicy_usaVbrHistorico() {
        assertEquals(List.of("-q:a", "2"), QualityPolicy.full().encodingArgs());
    }

    @Test
    @DisplayName("freeTier92() impone CBR 92 kbps: solo '-b:a 92k', jamas VBR ni otro bitrate")
    void freeTier_solo92kbps() {
        List<String> args = QualityPolicy.freeTier92().encodingArgs();
        assertAll(
                () -> assertEquals(List.of("-b:a", "92k"), args),
                () -> assertTrue(!args.contains("-q:a"), "el free tier no puede usar VBR"),
                () -> assertEquals("92k", args.get(1))
        );
    }

    @Test
    @DisplayName("cbrFixed valida el rango del bitrate")
    void cbrFixed_validaRango() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> QualityPolicy.cbrFixed(7)),
                () -> assertThrows(IllegalArgumentException.class, () -> QualityPolicy.cbrFixed(321)),
                () -> assertThrows(IllegalArgumentException.class, () -> QualityPolicy.cbrFixed(-92)),
                () -> assertEquals(List.of("-b:a", "8k"), QualityPolicy.cbrFixed(8).encodingArgs()),
                () -> assertEquals(List.of("-b:a", "320k"), QualityPolicy.cbrFixed(320).encodingArgs())
        );
    }

    // --- buildFfmpegCommand ------------------------------------------------------

    @Test
    @DisplayName("buildFfmpegCommand: la calidad se inserta tras libmp3lame, antes de la salida")
    void buildCommand_insertaPoliticaEnOrdenCorrecto() {
        var input = Path.of("origen", "cancion.wav");
        var output = Path.of("destino", "cancion.mp3");

        var full = Mp3ConversionService.buildFfmpegCommand(input, output, QualityPolicy.full());
        var lameIndex = full.indexOf("libmp3lame");
        var outputIndex = full.indexOf(output.toString());
        var qualityArgs = full.subList(lameIndex + 1, outputIndex);

        assertAll(
                () -> assertEquals("ffmpeg", full.get(0)),
                () -> assertTrue(full.contains(input.toString())),
                () -> assertTrue(full.contains(output.toString())),
                () -> assertEquals(List.of("-q:a", "2"), qualityArgs),
                () -> assertTrue(outputIndex > lameIndex, "la salida va al final")
        );
    }

    @Test
    @DisplayName("buildFfmpegCommand: el free tier fuerza -b:a 92k (regla del modelo de negocio)")
    void buildCommand_freeTierForcia92k() {
        var command = Mp3ConversionService.buildFfmpegCommand(
                Path.of("a.opus"), Path.of("b.mp3"), QualityPolicy.freeTier92());
        assertAll(
                () -> assertTrue(command.contains("92k")),
                () -> assertTrue(!command.contains("-q:a")),
                () -> assertEquals("92k", command.get(command.indexOf("-b:a") + 1))
        );
    }
}
