package com.casl.mp3converter.core.license;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Criptografía de utilería del core (requisitos 2.4, 2.5.2 de la fase 4):
 * <ul>
 *   <li>AES-256-GCM para confidencialidad de los payloads.</li>
 *   <li>HMAC-SHA256 para integridad.</li>
 *   <li>SHA-256 para hasheo de identificadores (correo).</li>
 * </ul>
 * NOTA de portfolio: las "master keys" viven embebidas en el binario (ver LicenseSecrets).
 * En un sistema real provienen de un backend/servidor seguro; la app sí protege contra
 * manipulación cursi pero no contra quien de-ensambla el binario.
 */
public final class CryptoUtil {

    private CryptoUtil() {
    }

    /** SHA-256 hex of any input string (UTF-8). */
    public static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** HKDF-less KDF: SHA-256 of concatenated material is enough for this demo scope. */
    public static byte[] deriveKey(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part + "\u0000").getBytes(StandardCharsets.UTF_8));
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** AES-256-GCM encrypt with a random 12-byte nonce; returns nonce||ciphertext||tag. */
    public static byte[] aesGcmEncrypt(byte[] key, byte[] plaintext) {
        try {
            byte[] nonce = new byte[12];
            new java.security.SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ciphertext, 0, out, nonce.length, ciphertext.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM falló", e);
        }
    }

    /** Decrypts {@code nonce||ciphertext||tag}; throws AEADBadTagException on tampering. */
    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonceCiphertext) {
        if (nonceCiphertext == null || nonceCiphertext.length <= 12) {
            throw new IllegalArgumentException("payload demasiado corto");
        }
        try {
            byte[] nonce = new byte[12];
            System.arraycopy(nonceCiphertext, 0, nonce, 0, nonce.length);
            byte[] ciphertext = new byte[nonceCiphertext.length - 12];
            System.arraycopy(nonceCiphertext, 12, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM: contenido inválido o manipulado", e);
        }
    }

    /** HMAC-SHA256 of the data with the given key. */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", e);
        }
    }
}
