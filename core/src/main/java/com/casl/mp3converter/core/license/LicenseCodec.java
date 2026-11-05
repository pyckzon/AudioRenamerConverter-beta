package com.casl.mp3converter.core.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Codec binario del archivo de licencia {@code mp3converter.lic} (requisito 2.4):
 * <pre>
 *   4B  magic "MPCL"
 *   1B  versión de formato (1)
 *   4B  longitud del cuerpo cifrado (big-endian)
 *   N   nonce(12) || ciphertext AES-256-GCM(payload) || tag
 *   32B HMAC-SHA256(bytes previos)  → cualquier byte alterado invalida la licencia
 * </pre>
 * Binario no transparente: el payload solo es legible por esta clase y los tests.
 */
public final class LicenseCodec {

    /** Identificador del contenido de una licencia. */
    public record LicensePayload(String tier, String emailHash, String activationCode, String issuedAtIso) {
    }

    static final byte[] MAGIC = {'M', 'P', 'C', 'L'};
    static final byte FORMAT_VERSION = 1;
    private static final int MAC_LENGTH = 32;
    private static final int LENGTH_FIELD_BYTES = 4;

    private LicenseCodec() {
    }

    /** Serializes + encrypts + signs the payload into the binary license bytes. */
    public static byte[] encode(LicensePayload payload) {
        byte[] plaintext = serialize(payload).getBytes(StandardCharsets.UTF_8);
        byte[] body = CryptoUtil.aesGcmEncrypt(LicenseSecrets.LICENSE_ENCRYPTION_KEY, plaintext);
        byte[] out = new byte[MAGIC.length + 1 + LENGTH_FIELD_BYTES + body.length + MAC_LENGTH];
        int pos = 0;
        System.arraycopy(MAGIC, 0, out, pos, MAGIC.length);
        pos += MAGIC.length;
        out[pos++] = FORMAT_VERSION;
        pos = writeInt32(out, pos, body.length);
        System.arraycopy(body, 0, out, pos, body.length);
        pos += body.length;
        byte[] mac = CryptoUtil.hmacSha256(LicenseSecrets.LICENSE_SEPARATOR_KEY,
                java.util.Arrays.copyOf(out, pos));
        System.arraycopy(mac, 0, out, pos, MAC_LENGTH);
        return out;
    }

    /** Descodes and verifies a license file; throws IllegalArgumentException on any issue. */
    public static LicensePayload decode(byte[] fileBytes) {
        if (fileBytes == null
                || fileBytes.length < MAGIC.length + 1 + LENGTH_FIELD_BYTES + MAC_LENGTH
                || fileBytes[0] != MAGIC[0] || fileBytes[1] != MAGIC[1]
                || fileBytes[2] != MAGIC[2] || fileBytes[3] != MAGIC[3]) {
            throw new IllegalArgumentException("no es una licencia mp3Converter");
        }
        int endOfSigned = fileBytes.length - MAC_LENGTH;
        byte[] expectedMac = CryptoUtil.hmacSha256(LicenseSecrets.LICENSE_SEPARATOR_KEY,
                java.util.Arrays.copyOf(fileBytes, endOfSigned));
        for (int i = 0; i < MAC_LENGTH; i++) {
            if (expectedMac[i] != fileBytes[endOfSigned + i]) {
                throw new IllegalArgumentException("licencia manipulada (HMAC inválido)");
            }
        }
        int pos = MAGIC.length;
        byte version = fileBytes[pos++];
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("formato de licencia desconocido v" + version);
        }
        int bodyLength = readInt32(fileBytes, pos);
        pos += LENGTH_FIELD_BYTES;
        if (pos + bodyLength != endOfSigned || bodyLength <= 12) {
            throw new IllegalArgumentException("archivo de licencia corrupto");
        }
        byte[] body = new byte[bodyLength];
        System.arraycopy(fileBytes, pos, body, 0, bodyLength);
        byte[] plaintext;
        try {
            plaintext = CryptoUtil.aesGcmDecrypt(LicenseSecrets.LICENSE_ENCRYPTION_KEY, body);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("licencia ilegible", e);
        }
        return deserialize(new String(plaintext, StandardCharsets.UTF_8));
    }

    /** Reads the license file if it exists; null when absent OR corrupt/tampered (tier FREE). */
    public static LicensePayload read(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            return decode(Files.readAllBytes(file));
        } catch (IllegalArgumentException | java.io.IOException e) {
            return null; // tampered/corrupt => treated as absent (tier FREE)
        }
    }

    /** Serializes to "k=v;..." canonical text (no JSON needed for this fixed payload). */
    static String serialize(LicensePayload payload) {
        return "tier=" + payload.tier()
                + ";emailHash=" + payload.emailHash()
                + ";activationCode=" + payload.activationCode()
                + ";issuedAt=" + payload.issuedAtIso();
    }

    static LicensePayload deserialize(String text) {
        Map<String, String> fields = new HashMap<>();
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                fields.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        String tier = fields.getOrDefault("tier", "FREE");
        if (!"PRO".equals(tier)) {
            throw new IllegalArgumentException("licencia sin tier PRO");
        }
        return new LicensePayload(tier,
                fields.getOrDefault("emailHash", ""),
                fields.getOrDefault("activationCode", ""),
                fields.getOrDefault("issuedAt", Instant.now().toString()));
    }

    private static int writeInt32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
        return offset + LENGTH_FIELD_BYTES;
    }

    private static int readInt32(byte[] source, int offset) {
        return ((source[offset] & 0xff) << 24)
                | ((source[offset + 1] & 0xff) << 16)
                | ((source[offset + 2] & 0xff) << 8)
                | (source[offset + 3] & 0xff);
    }
}
