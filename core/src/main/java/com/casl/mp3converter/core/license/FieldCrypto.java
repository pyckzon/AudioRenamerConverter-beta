package com.casl.mp3converter.core.license;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Cifrado por campo de los datos del usuario en Supabase (requisito 2.5.2):
 * <ul>
 *   <li>Derivación de clave {@code PBKDF2WithHmacSHA256} con password = correo +
 *       pepper = apellido del usuario, y salt aleatorio de 16 bytes persistido por usuario.</li>
 *   <li>Cada campo (nombre, apellido, correo, teléfono, código de activación) viaja
 *       cifrado con AES-256-GCM bajo esa clave (ver {@link CryptoUtil}).</li>
 *   <li>Identificadores de búsqueda ({@code email_hash}, {@code code_hash}) se guardan
 *       como HMAC-SHA256 con el secreto de licencia del binario: lookup rápido sin
 *       material de mapa en claro.</li>
 * </ul>
 * La semántica esCase-sensible al TAL CUAL del formulario: el apellido con/sin tilde
 * deriva claves distintas, así que el formulario (fase 5) envía los valores tal cual
 * y la recuperación usa exactamente la misma pareja correo+apellido.
 */
public final class FieldCrypto {

    /** Iteraciones PBKDF2: parámetro típico actual para OWASP (600k recomienda; desktop ok). */
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private FieldCrypto() {
    }

    /** Nuevo salt aleatorio de 16 bytes, se persiste por usuario en {@code users.salt}. */
    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    /**
     * Clave del usuario: PBKDF2(password = correo + pepper(apellido), saltAleatorio).
     * El "correo" se normaliza (trim/lowercase) para tolerar mayúsculas en el email;
     * el "apellido" NO se normaliza: es pepper tal cual el usuario lo escribió.
     */
    public static byte[] deriveUserKey(String correo, String apellido, byte[] salt) {
        try {
            String correoNormalizado = correo == null ? "" : correo.strip().toLowerCase(Locale.ROOT);
            String password = correoNormalizado + ":" + (apellido == null ? "" : apellido);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 no disponible", e);
        }
    }

    /** AES-GCM del campo bajo la clave del usuario. null/vacío => array vacío. */
    public static byte[] encryptField(String correo, String apellido, byte[] salt, String plaintext) {
        byte[] key = deriveUserKey(correo, apellido, salt);
        if (plaintext == null || plaintext.isEmpty()) {
            return new byte[0];
        }
        return CryptoUtil.aesGcmEncrypt(key, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Descifra un campo; el PEPPER incorrecto (apellido distinto) no descifra:
     * lanza {@code IllegalStateException} (firma del requisito 2.5.2 en mapa de pruebas).
     */
    public static String decryptField(String correo, String apellido, byte[] salt, byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length == 0) {
            return "";
        }
        byte[] key = deriveUserKey(correo, apellido, salt);
        byte[] plain = CryptoUtil.aesGcmDecrypt(key, ciphertext);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * email_hash para betrachten sin material en claro y des-duplicar:
     * HMAC-SHA256(correo normalizado, secreto del binario) => incluso si se filtra
     * la fila no se puede regenerar sin el binario.
     */
    public static String emailHash(String correo) {
        String normalized = correo == null ? "" : correo.strip().toLowerCase(Locale.ROOT);
        return HexFormat.of().formatHex(
                CryptoUtil.hmacSha256(LicenseSecrets.LICENSE_SEPARATOR_KEY,
                        normalized.getBytes(StandardCharsets.UTF_8)));
    }

    /** code_hash para lookup del código de activación (mismo esquema que emailHash). */
    public static String codeHash(String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        return HexFormat.of().formatHex(
                CryptoUtil.hmacSha256(LicenseSecrets.LICENSE_SEPARATOR_KEY,
                        normalized.getBytes(StandardCharsets.UTF_8)));
    }

    /** Comparación en tiempo constante de un hash de código contra el esperado. */
    public static boolean codeHashMatches(String candidateHash, String expectedHash) {
        if (candidateHash == null || expectedHash == null
                || candidateHash.length() != expectedHash.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                HexFormat.of().parseHex(candidateHash),
                HexFormat.of().parseHex(expectedHash));
    }
}
