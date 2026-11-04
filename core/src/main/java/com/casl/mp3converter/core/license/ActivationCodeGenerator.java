package com.casl.mp3converter.core.license;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Código de activación del contrato "Pro" (requisito 2.6):
 * formato estable {@code MP3C-XXXX-XXXX-XXXX}, alfabeto sin ambigüedades
 * (sin I/O/1), generado con {@link SecureRandom} (~62 bits de entropía).
 * Se PERSISTE hasheado ({@link FieldCrypto#codeHash}) para lookup y
 * cifrado ({@code code_enc}) para recuperación del usuario.
 */
public final class ActivationCodeGenerator {

    /** Letras/dígitos claramente distinguibles: sin 0/O, 1/I/L. */
    private static final char[] ALPHABET =
            "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int GROUP_LENGTH = 4;
    private static final int GROUPS = 3;
    private static final String PREFIX = "MP3C";
    private static final String SEPARATOR = "-";

    private ActivationCodeGenerator() {
    }

    /** Nuevo código aleatorio "MP3C-XXXX-XXXX-XXXX" (mayúsculas). */
    public static String generate() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder(PREFIX);
        for (int g = 0; g < GROUPS; g++) {
            code.append(SEPARATOR);
            for (int c = 0; c < GROUP_LENGTH; c++) {
                code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
        }
        return code.toString();
    }

    /** Formato estable del requisito 2.6: PREFIJO + 3 grupos de 4 caracteres del alfabeto. */
    public static boolean isFormatValid(String code) {
        if (code == null) {
            return false;
        }
        String[] parts = code.strip().toUpperCase().split(SEPARATOR);
        if (parts.length != GROUPS + 1 || !PREFIX.equals(parts[0])) {
            return false;
        }
        for (int g = 1; g <= GROUPS; g++) {
            if (parts[g].length() != GROUP_LENGTH) {
                return false;
            }
            for (char c : parts[g].toCharArray()) {
                if (new String(ALPHABET).indexOf(c) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Lote de códigos distintos (para pruebas / regeneración). */
    public static Set<String> generateMany(int count) {
        Set<String> seen = new HashSet<>();
        while (seen.size() < count) {
            seen.add(generate());
        }
        return seen;
    }
}
