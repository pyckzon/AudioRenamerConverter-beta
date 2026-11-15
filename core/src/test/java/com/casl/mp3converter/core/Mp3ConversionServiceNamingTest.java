package com.casl.mp3converter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming rules of the output MP3 (feature restored from the Audio Renamer console tool):
 * final name = "{artista de los metadatos} - {nombre original menos su (xxx) final}".
 * A missing artist falls back to the historical simple name.
 */
@DisplayName("Nomenclatura del MP3 resultante")
class Mp3ConversionServiceNamingTest {

    private final Mp3ConversionService service = new Mp3ConversionService();

    /** Service whose metadata "reader" is stubbed: forces the artist tag value. */
    private static Mp3ConversionService withArtist(String artist) {
        return new Mp3ConversionService() {
            @Override
            String readArtist(Path audioFile) {
                return artist;
            }
        };
    }

    /** resolveOutputName unwrapped: the naming logic never throws I/O in this contract. */
    private static String out(Mp3ConversionService svc, Path file) {
        try {
            return svc.resolveOutputName(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- stripTrailingParenthetical -------------------------------------------

    @Test
    @DisplayName("stripTrailingParenthetical elimina el grupo final '(xxx)' del nombre")
    void stripTrailingParenthetical_variosCasos() {
        assertAll(
                () -> assertEquals("Digital Love", service.stripTrailingParenthetical("Digital Love (152kbit_Opus)")),
                () -> assertEquals("Song", service.stripTrailingParenthetical("Song (Official Audio)")),
                () -> assertEquals("Puro", service.stripTrailingParenthetical("Puro")),
                // solo el ÚLTIMO grupo: lo interior se conserva
                () -> assertEquals("Dos (1) Dos", service.stripTrailingParenthetical("Dos (1) Dos (2)")),
                () -> assertEquals("", service.stripTrailingParenthetical(null))
        );
    }

    // --- sanitizeWindowsFileName ----------------------------------------------

    @Test
    @DisplayName("sanitizeWindowsFileName neutraliza caracteres y nombres reservados de Windows")
    void sanitizeWindowsFileName_variosCasos() {
        assertAll(
                () -> assertEquals("a___b", service.sanitizeWindowsFileName("a<|>b")),
                () -> assertEquals("v_a", service.sanitizeWindowsFileName("v\\a")),
                () -> assertEquals("_NUL", service.sanitizeWindowsFileName("NUL")),
                () -> assertEquals("audio", service.sanitizeWindowsFileName("   ")),
                () -> assertEquals("sin puntos finales", service.sanitizeWindowsFileName("sin puntos finales.")),
                () -> assertTrue(service.sanitizeWindowsFileName("x".repeat(300)).length() <= 200)
        );
    }

    // --- resolveOutputName ------------------------------------------------------

    @Test
    @DisplayName("con artista en metadatos: '<artista> - <nombre sin (xxx)>'")
    void resolveOutputName_conArtista(@TempDir Path dir) {
        var file = dir.resolve("Digital Love (152kbit_Opus).opus");
        var named = withArtist("Daft Punk");

        assertEquals("Daft Punk - Digital Love", out(named, file));
    }

    @Test
    @DisplayName("artista de export de YouTube (' - Topic') se limpia antes de componer el nombre")
    void resolveOutputName_conArtistaTopic(@TempDir Path dir) {
        var file = dir.resolve("Digital Love.opus");
        var named = withArtist("Daft Punk - Topic");

        assertEquals("Daft Punk - Digital Love", out(named, file));
    }

    @Test
    @DisplayName("sin artista en metadatos: nombre simple sin (xxx) (comportamiento histórico)")
    void resolveOutputName_sinArtista_usaNombreSimple(@TempDir Path dir) {
        var file = dir.resolve("solo pista (192kbit).wav");
        var named = withArtist("");

        assertEquals("solo pista", out(named, file));
    }

    @Test
    @DisplayName("artista con caracteres prohibidos en Windows: se neutraliza en el nombre final")
    void resolveOutputName_artistaConCaracteresProhibidos(@TempDir Path dir) {
        var file = dir.resolve("cancion (acústica).flac");
        var named = withArtist("Grupo \"Alto\"");
        // las comillas dobles se reemplazan por _
        assertEquals("Grupo _Alto_ - cancion", out(named, file));
    }
}
