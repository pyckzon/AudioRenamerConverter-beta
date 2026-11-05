package com.casl.mp3converter.core.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of the crypto primitives used by the binary license (requisito 2.4)
 * and by the encrypted persistence of fase 4 (requisito 2.5.2).
 */
@DisplayName("CryptoUtil - AES-256-GCM, HMAC-SHA256 y SHA-256")
class CryptoUtilTest {

    @Test
    @DisplayName("sha256Hex produce el digest conocido y estable")
    void sha256_determinista() {
        // Vector de referencia publicado del algoritmo:
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                CryptoUtil.sha256Hex(""));
        // Vector publicado: SHA-256 de "hello world":
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                CryptoUtil.sha256Hex("hello world"));
        // estabilidad sobre el uso real del proyecto:
        assertEquals(CryptoUtil.sha256Hex("mp3converter"), CryptoUtil.sha256Hex("mp3converter"));
        assertNotEquals(CryptoUtil.sha256Hex("a"), CryptoUtil.sha256Hex("b"));
    }

    @Test
    @DisplayName("hmacSha256 detecta cualquier alteración del dato o de la llave")
    void hmac_detectaAltaciones() {
        byte[] key = "llave-hmac".getBytes(StandardCharsets.UTF_8);
        byte[] data = "payload-de-prueba".getBytes(StandardCharsets.UTF_8);

        byte[] mac = CryptoUtil.hmacSha256(key, data);

        assertAll(
                () -> assertEquals(32, mac.length, "HMAC-SHA256 son 32 bytes"),
                () -> assertArrayEquals(mac, CryptoUtil.hmacSha256(key, data), "determinista"),
                () -> assertNotEquals(HexFormat.of().formatHex(mac),
                        HexFormat.of().formatHex(
                                CryptoUtil.hmacSha256(key, ("payload-de-pruebaX").getBytes(StandardCharsets.UTF_8))),
                        "un byte distinto del dato cambia el mac"),
                () -> assertNotEquals(HexFormat.of().formatHex(mac),
                        HexFormat.of().formatHex(
                                CryptoUtil.hmacSha256("otra-llave".getBytes(StandardCharsets.UTF_8), data)),
                        "otra llave cambia el mac")
        );
    }

    @Test
    @DisplayName("AES-GCM roundtrip: nonce(12)||ct||tag descifra al texto original")
    void aesGcm_roundtrip() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        byte[] plaintext = "tier=PRO;activationCode=ABCD-1234".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = CryptoUtil.aesGcmEncrypt(key, plaintext);
        byte[] decrypted = CryptoUtil.aesGcmDecrypt(key, encrypted);

        assertAll(
                () -> assertEquals(12 + plaintext.length + 16, encrypted.length,
                        "nonce(12) + ciphertext + tag(16)"),
                () -> assertArrayEquals(plaintext, decrypted),
                // mismo texto dos veces: nonce aleatorio => ciphertext distinto (indeterminismo seguro):
                () -> assertNotEquals(
                        HexFormat.of().formatHex(encrypted),
                        HexFormat.of().formatHex(CryptoUtil.aesGcmEncrypt(key, plaintext)))
        );
    }

    @Test
    @DisplayName("AES-GCM falla si se altera un solo byte del payload cifrado")
    void aesGcm_tamperFails() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        byte[] encrypted = CryptoUtil.aesGcmEncrypt(key, "datos".getBytes(StandardCharsets.UTF_8));

        encrypted[13] ^= 0x01; // flip de un bit dentro del ciphertext

        assertThrows(IllegalStateException.class, () -> CryptoUtil.aesGcmDecrypt(key, encrypted));
    }

    @Test
    @DisplayName("AES-GCM con llave distinta no descifra, y payload corto se rechaza")
    void aesGcm_entradasInvalidas() {
        byte[] keyA = new byte[32];
        byte[] keyB = new byte[32];
        new java.security.SecureRandom().nextBytes(keyA);
        new java.security.SecureRandom().nextBytes(keyB);

        byte[] encrypted = CryptoUtil.aesGcmEncrypt(keyA, "secreto".getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> CryptoUtil.aesGcmDecrypt(keyB, encrypted)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> CryptoUtil.aesGcmDecrypt(keyA, new byte[12]))
        );
    }

    @Test
    @DisplayName("deriveKey produce 32 bytes estables y sensibles al material")
    void deriveKey_estable() {
        byte[] a = CryptoUtil.deriveKey("correo@x.com", "apellido");
        byte[] b = CryptoUtil.deriveKey("correo@x.com", "apellido");

        assertAll(
                () -> assertEquals(32, a.length),
                () -> assertArrayEquals(a, b),
                () -> assertNotEquals(HexFormat.of().formatHex(a),
                        HexFormat.of().formatHex(CryptoUtil.deriveKey("correo@x.com", "otroApellido")))
        );
    }

    @Test
    @DisplayName("regla de negocio: solo acepta llaves AES de 32 bytes (AES-256)")
    void aesGcm_rechazaLlaveCorta() {
        byte[] shortKey = new byte[30];
        new java.security.SecureRandom().nextBytes(shortKey);

        assertTrue(assertThrows(IllegalStateException.class,
                () -> CryptoUtil.aesGcmEncrypt(shortKey, "x".getBytes(StandardCharsets.UTF_8)))
                .getMessage().toLowerCase().contains("aes"));
    }
}
