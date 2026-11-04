package com.casl.mp3converter.core.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requisito 2.4: el archivo de licencia es un binario junto al .exe, protegido con
 * AES-256-GCM + HMAC-SHA256; corrupto/manipulado => se ignora (FREE).
 */
@DisplayName("LicenseCodec - binario MPCL con AES-GCM + HMAC")
class LicenseCodecTest {

    private static final LicenseCodec.LicensePayload PRO_PAYLOAD =
            new LicenseCodec.LicensePayload("PRO", "hash-correo", "MP3C-TEST-0001", "2026-11-04T00:00:00Z");

    @Test
    @DisplayName("roundtrip: encode -> decode devuelve el payload exacto")
    void roundtrip() {
        byte[] bytes = LicenseCodec.encode(PRO_PAYLOAD);
        LicenseCodec.LicensePayload decoded = LicenseCodec.decode(bytes);

        assertAll(
                () -> assertEquals("PRO", decoded.tier()),
                () -> assertEquals("hash-correo", decoded.emailHash()),
                () -> assertEquals("MP3C-TEST-0001", decoded.activationCode()),
                () -> assertEquals("2026-11-04T00:00:00Z", decoded.issuedAtIso())
        );
    }

    @Test
    @DisplayName("la marca de agua del formato (magic + longitud) es consistente con la especificación")
    void formato_bytesConMPCL() {
        byte[] bytes = LicenseCodec.encode(PRO_PAYLOAD);

        assertAll(
                () -> assertEquals('M', bytes[0]),
                () -> assertEquals('P', bytes[1]),
                () -> assertEquals('C', bytes[2]),
                () -> assertEquals('L', bytes[3]),
                () -> assertEquals(1, bytes[4], "versión de formato"),
                () -> assertEquals(LicenseCodec.MAGIC.length + 1 + 4
                        + (12 + LicenseCodec.serialize(PRO_PAYLOAD).getBytes().length + 16)
                        + 32, bytes.length),
                () -> assertTrue(bytes.length >= LicenseCodec.MAGIC.length + 1 + 4 + 32)
        );
    }

    @Test
    @DisplayName("tamper: alterar UN byte del archivo => HMAC inválido / decode falla")
    void tamperedByte_failsDecoding() {
        byte[] bytes = LicenseCodec.encode(PRO_PAYLOAD);
        byte[] tampered = bytes.clone();
        tampered[7] ^= 0x01;

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> LicenseCodec.decode(tampered)),
                () -> assertEquals("licencia manipulada (HMAC inválido)",
                        assertThrows(IllegalArgumentException.class,
                                () -> LicenseCodec.decode(tampered)).getMessage())
        );
    }

    @Test
    @DisplayName("tamper del HMAC mismo: último byte cambiado => rechazado")
    void tamperedMac_fails() {
        byte[] bytes = LicenseCodec.encode(PRO_PAYLOAD);
        byte[] tampered = bytes.clone();
        tampered[tampered.length - 1] ^= 0xFF;

        assertThrows(IllegalArgumentException.class, () -> LicenseCodec.decode(tampered));
    }

    @Test
    @DisplayName("archivo truncado => rechazado (no hay suficiente material para el HMAC)")
    void truncated_fails() {
        byte[] bytes = LicenseCodec.encode(PRO_PAYLOAD);

        byte[] truncated = new byte[bytes.length - 5];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);

        assertThrows(IllegalArgumentException.class, () -> LicenseCodec.decode(truncated));
    }

    @Test
    @DisplayName("magic incorrecto => NO es una licencia mp3Converter")
    void wrongMagic_fails() {
        byte[] bytes = LicenseCodec.encode(PRO_PAYLOAD);
        byte[] others = bytes.clone();
        others[0] = 'X';

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> LicenseCodec.decode(others)),
                () -> assertEquals("no es una licencia mp3Converter",
                        assertThrows(IllegalArgumentException.class,
                                () -> LicenseCodec.decode(others)).getMessage()),
                () -> assertThrows(IllegalArgumentException.class, () -> LicenseCodec.decode(new byte[10])),
                () -> assertThrows(IllegalArgumentException.class, () -> LicenseCodec.decode(null))
        );
    }

    @Test
    @DisplayName("payload con tier != PRO es ilegible como licencia PRO (invariante de negocio)")
    void freeTier_deserializaComoInvalido() {
        String texto = "tier=FREE;emailHash=x;activationCode=y;issuedAt=now";
        assertThrows(IllegalArgumentException.class, () -> LicenseCodec.deserialize(texto));
    }

    @Test
    @DisplayName("read(): absent/corrupto => null; válido => payload (jurisdiction del verificador: FREE)")
    void read_pathSemantics(@TempDir Path dir) throws Exception {
        Path valid = dir.resolve("lic-ok.lic");
        Files.write(valid, LicenseCodec.encode(PRO_PAYLOAD));

        Path corrupted = dir.resolve("lic-bad.lic");
        byte[] bytes = LicenseCodec.encode(PRO_PAYLOAD);
        bytes[10] ^= 0x55;
        Files.write(corrupted, bytes);

        Path absent = dir.resolve("no-existe.lic");

        assertAll(
                () -> assertEquals("PRO", LicenseCodec.read(valid).tier()),
                () -> assertEquals(null, LicenseCodec.read(corrupted)),
                () -> assertEquals(null, LicenseCodec.read(absent))
        );
    }

    @Test
    @DisplayName("el binario NO es transparente: dos codificaciones del mismo payload difieren (nonce aleatorio)")
    void encode_noDeterminista_porNonce() {
        byte[] a = LicenseCodec.encode(PRO_PAYLOAD);
        byte[] b = LicenseCodec.encode(PRO_PAYLOAD);

        assertNotEquals(java.util.HexFormat.of().formatHex(a),
                java.util.HexFormat.of().formatHex(b));
    }
}
