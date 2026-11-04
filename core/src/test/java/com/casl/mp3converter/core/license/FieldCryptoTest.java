package com.casl.mp3converter.core.license;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cifrado por campo con sal=correo y pepper=apellido (requisito 2.5.2),
 * y el generador de códigos de activación (requisito 2.6).
 */
@DisplayName("FieldCrypto + ActivationCodeGenerator (requisitos 2.5.2 y 2.6)")
class FieldCryptoTest {

    private static final String CORREO = "ana.garcia@test.com";
    private static final String APELLIDO = "García";
    private static final String NOMBRE = "Ana Laura";
    private static final String TELEFONO = "+52 55 1234 5678";

    // --- 2.5.2 Sal/pepper -----------------------------------------------------

    @Test
    @DisplayName("2.5.2 round-trip: mismo correo+apellido recupera el campo original")
    void roundtrip() {
        byte[] salt = FieldCrypto.newSalt();
        byte[] encrypted = FieldCrypto.encryptField(CORREO, APELLIDO, salt, NOMBRE);

        assertEquals(NOMBRE, FieldCrypto.decryptField(CORREO, APELLIDO, salt, encrypted));
        assertEquals(TELEFONO, FieldCrypto.decryptField(CORREO, APELLIDO, salt,
                FieldCrypto.encryptField(CORREO, APELLIDO, salt, TELEFONO)));
    }

    @Test
    @DisplayName("2.5.2 misma DATA, CORREO distinto (sal) => ciphertexts distintos (mapa de pruebas)")
    void salDistinta_ciphertextDistinto() {
        byte[] saltCompartida = FieldCrypto.newSalt();
        byte[] conCorreo1 = FieldCrypto.encryptField("ana.garcia@test.com", APELLIDO, saltCompartida, NOMBRE);
        byte[] conCorreo2 = FieldCrypto.encryptField("otro.correo@test.com", APELLIDO, saltCompartida, NOMBRE);

        assertNotEquals(HexFormat.of().formatHex(conCorreo1), HexFormat.of().formatHex(conCorreo2));
        // y NO se descifra cruzado:
        assertThrows(IllegalStateException.class,
                () -> FieldCrypto.decryptField("otro.correo@test.com", APELLIDO, saltCompartida, conCorreo1));
    }

    @Test
    @DisplayName("2.5.2 PEPPER incorrecto (apellido distinto) NO descifra")
    void pepperIncorrecto_noDescifra() {
        byte[] salt = FieldCrypto.newSalt();
        byte[] encrypted = FieldCrypto.encryptField(CORREO, APELLIDO, salt, NOMBRE);

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> FieldCrypto.decryptField(CORREO, "Garcia", salt, encrypted),
                        "apellido sin tilde es OTRO pepper"),
                () -> assertThrows(IllegalStateException.class,
                        () -> FieldCrypto.decryptField(CORREO, "Pérez", salt, encrypted)),
                () -> assertThrows(IllegalStateException.class,
                        () -> FieldCrypto.decryptField("otro@test.com", APELLIDO, salt, encrypted))
        );
    }

    @Test
    @DisplayName("2.5.2 salt del usuario: valores aleatorios, no vacíos, no repetidos")
    void salt_aleatoria() {
        byte[] s1 = FieldCrypto.newSalt();
        byte[] s2 = FieldCrypto.newSalt();

        assertAll(
                () -> assertEquals(16, s1.length),
                () -> assertFalse(java.util.Arrays.equals(s1, s2))
        );
    }

    @Test
    @DisplayName("2.5.2 fórmula: PBKDF2-210k-SHA256 sobre correo+pepper, 256 bits")
    void deriveKey_tamanoEstable() {
        byte[] salt = FieldCrypto.newSalt();
        byte[] k1 = FieldCrypto.deriveUserKey(CORREO, APELLIDO, salt);
        byte[] k2 = FieldCrypto.deriveUserKey("  ANA.GARCIA@TEST.COM  ", APELLIDO, salt);

        assertAll(
                () -> assertEquals(32, k1.length),
                () -> assertArrayEquals(k1, k2, "correo normalizado (trim/lowercase) es sal estable"),
                () -> assertNotEquals(HexFormat.of().formatHex(k1),
                        HexFormat.of().formatHex(FieldCrypto.deriveUserKey(CORREO, "Garcia", salt)))
        );
    }

    @Test
    @DisplayName("emailHash/codeHash: estables, distintos entre sí, sin material en claro")
    void hashes_deLookup() {
        assertAll(
                () -> assertEquals(FieldCrypto.emailHash(CORREO.toUpperCase()), FieldCrypto.emailHash(CORREO)),
                () -> assertNotEquals(FieldCrypto.emailHash(CORREO), FieldCrypto.codeHash("MP3C-AAAA-BBBB-CCCC")),
                () -> assertTrue(FieldCrypto.codeHashMatches(FieldCrypto.codeHash("MP3C-KV2M-N5XT-8QRW"),
                        FieldCrypto.codeHash("mp3c-kv2m-n5xt-8qrw")), "case-insensitive"),
                () -> assertFalse(FieldCrypto.codeHashMatches(FieldCrypto.codeHash("MP3C-A"),
                        FieldCrypto.codeHash("MP3C-B")))
        );
    }

    // --- 2.6 Código de activación --------------------------------------------

    @Test
    @DisplayName("2.6 formato estable MP3C-XXXX-XXXX-XXXX, alfabeto sin 0/O/1/I/L")
    void formatoDelCodigo() {
        String code = ActivationCodeGenerator.generate();

        assertAll(
                () -> assertTrue(Pattern.matches("MP3C-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}", code),
                        "formato del requisito 2.6: " + code),
                () -> assertTrue(ActivationCodeGenerator.isFormatValid(code)),
                () -> assertTrue(ActivationCodeGenerator.isFormatValid(code.toLowerCase()),
                        "acepta minusculas (lookup normaliza)"),
                () -> assertFalse(ActivationCodeGenerator.isFormatValid("MP3C-ABCD-EFGH-IOL1"),
                        "alfabeto prohibe 0/1/I/L/O... I,O,1,0 no estan; L tampoco"),
                () -> assertFalse(ActivationCodeGenerator.isFormatValid("XXXX-AAAA-BBBB-CCCC")),
                () -> assertFalse(ActivationCodeGenerator.isFormatValid("MP3C-AAAA")),
                () -> assertFalse(ActivationCodeGenerator.isFormatValid(null))
        );
    }

    @Test
    @DisplayName("2.6 unicidad: lote grande sin repeticiones")
    void unicidad() {
        Set<String> codes = ActivationCodeGenerator.generateMany(500);
        assertEquals(500, codes.size());
    }

    @Test
    @DisplayName("2.6 código correcto aceptado / código falso rechazado (hash)")
    void codigoFalso_rechazado() {
        String code = ActivationCodeGenerator.generate();
        String hash = FieldCrypto.codeHash(code);

        assertAll(
                () -> assertTrue(FieldCrypto.codeHashMatches(FieldCrypto.codeHash(code), hash)),
                () -> assertFalse(FieldCrypto.codeHashMatches(
                        FieldCrypto.codeHash(ActivationCodeGenerator.generate()), hash))
        );
    }

    @Test
    @DisplayName("campo vacío/null: roundtrip de string vacío es un array vacío")
    void camposVacios() {
        byte[] salt = FieldCrypto.newSalt();
        assertAll(
                () -> assertDoesNotThrow(() -> FieldCrypto.encryptField(CORREO, APELLIDO, salt, "")),
                () -> assertEquals(0, FieldCrypto.encryptField(CORREO, APELLIDO, salt, "").length),
                () -> assertEquals("", FieldCrypto.decryptField(CORREO, APELLIDO, salt, new byte[0]))
        );
    }
}
