package com.casl.mp3converter.core.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapa de pruebas 2.5 del plan: caso feliz por campo + rechazos documentados
 * (15/17 dígitos de tarjeta, CVV 2/4, vencimientos inválidos/expirados, nombre
 * con dígito/símbolo/1 letra, dirección de 71 chars o sin '#', teléfono de 9/11,
 * correo sin @/dominio) y con acentos/ñ VÁLIDOS.
 */
@DisplayName("PaymentValidator - reglas exactas del formulario (requisito 2.5)")
class PaymentValidatorTest {

    // --- Tarjeta + Luhn ----------------------------------------------------------

    @Test
    @DisplayName("tarjeta feliz: 16 dígitos con Luhn válido, espacios visuales se limpian")
    void tarjetaFeliz() {
        assertAll(
                () -> assertTrue(PaymentValidator.validateCardNumber("4111 1111 1111 1111").isPresent()),
                () -> assertTrue(PaymentValidator.validateCardNumber("5555 5555 5555 4444").isPresent())
        );
    }

    @ParameterizedTest(name = "tarjeta inválida: {0}")
    @DisplayName("rechazos exactos de la tarjeta:")
    @ValueSource(strings = {"411111111111111", // 15 dígitos
            "41111111111111111", // 17 dígitos
            "411111111111", // 12 dígitos
            "4111 1111 1111 1119", // 16 pero Luhn inválido
            "4444444444444449", // 16 no-Luhn
            "abcd1234efgh5678"})
    void tarjetaInvalida(String card) {
        assertTrue(PaymentValidator.validateCardNumber(card).isEmpty());
    }

    @Test
    @DisplayName("Luhn algoritmo: casos documentados")
    void luhn() {
        assertAll(
                () -> assertTrue(PaymentValidator.luhnValid("4111111111111111")),
                () -> assertFalse(PaymentValidator.luhnValid("4111111111111112")),
                () -> assertFalse(PaymentValidator.luhnValid("1234"))
        );
    }

    // --- Vencimiento ------------------------------------------------------------

    @ParameterizedTest(name = "vencimiento inválido: {0}")
    @DisplayName("vencimientos inválidos/expirados:")
    @ValueSource(strings = {"13/2098", "00/2098", "13/2099", "01/2020", "02/2026",
            "12/202732", "1/2030", "0101/2234", "10/20245"})
    void vencimientoInvalido(String expiry) {
        assertTrue(PaymentValidator.validateExpiry(expiry).isEmpty());
    }

    @Test
    @DisplayName("vencimientos felices: año 2099 y el mes EN CURSO aún vale (no expirado)")
    void vencimientoFeliz() {
        var ahora = java.time.YearMonth.now();
        String thisMonth = String.format("%02d/%04d", ahora.getMonthValue(), ahora.getYear());
        assertAll(
                () -> assertTrue(PaymentValidator.validateExpiry("12/2099").isPresent()),
                () -> assertTrue(PaymentValidator.validateExpiry(thisMonth).isPresent(),
                        "la tarjeta del mes en curso NO está vencida")
        );
    }

    // --- CVV ----------------------------------------------------------------------

    @Test
    @DisplayName("CVV: 3 dígitos exactos; 2 y 4 se rechazan")
    void cvv() {
        assertAll(
                () -> assertTrue(PaymentValidator.validateCvv("123").isPresent()),
                () -> assertTrue(PaymentValidator.validateCvv("12").isEmpty()),
                () -> assertTrue(PaymentValidator.validateCvv("1234").isEmpty()),
                () -> assertTrue(PaymentValidator.validateCvv("1a3").isEmpty())
        );
    }

    // --- Nombre / Apellido ----------------------------------------------------------

    @Test
    @DisplayName("nombre/apellido: acentos y ñ son letras VÁLIDAS")
    void nombresConAcentosValidos() {
        assertAll(
                () -> assertTrue(PaymentValidator.validateNombre("Ángél Gonzàlez").isPresent()),
                () -> assertTrue(PaymentValidator.validateApellido("Müñoz Estrada").isPresent()),
                () -> assertTrue(PaymentValidator.validateNombre("María").isPresent()),
                () -> assertTrue(PaymentValidator.validateNombre("Núñez").isPresent())
        );
    }

    @ParameterizedTest(name = "nombre inválido: {0}")
    @DisplayName("rechazos exactos del nombre:")
    @ValueSource(strings = {"A", "Ana123", "1Ana", "Ana-P", "Maria!", "Ana Maria  1", "  "})
    void nombreInvalido(String nombre) {
        assertAll(
                () -> assertTrue(PaymentValidator.validateNombre(nombre).isEmpty()),
                () -> assertTrue(PaymentValidator.validateApellido(nombre).isEmpty())
        );
    }

    // --- Correo ---------------------------------------------------------------------

    @ParameterizedTest(name = "correo inválido: {0}")
    @DisplayName("rechazos exactos del correo:")
    @ValueSource(strings = {"sinat", "a@", "@dominio.com", "a@dominio", "a b@test.com", ""})
    void correoInvalido(String correo) {
        assertTrue(PaymentValidator.validOrNormalizeEmail(correo).isEmpty());
    }

    @Test
    @DisplayName("correo feliz se NORMALIZA (trim + lowercase)")
    void correoFeliz() {
        assertAll(
                () -> assertEquals("ana.garcia@casl.dev",
                        PaymentValidator.validOrNormalizeEmail("  ANA.GARCIA@CASL.DEV ").orElse("x")),
                () -> assertTrue(PaymentValidator.validOrNormalizeEmail("usuario+tag@sub.dominio.mx").isPresent())
        );
    }

    // --- Teléfono --------------------------------------------------------------------

    @Test
    @DisplayName("teléfono: exactamente 10 dígitos (9 y 11 se rechazan)")
    void telefono() {
        assertAll(
                () -> assertTrue(PaymentValidator.validatePhoneDigits("55 1234 5678").isPresent()),
                () -> assertTrue(PaymentValidator.validatePhoneDigits("551234567").isEmpty()),
                () -> assertTrue(PaymentValidator.validatePhoneDigits("55123456789").isEmpty()),
                () -> assertTrue(PaymentValidator.validatePhoneDigits("55a3456780").isEmpty())
        );
    }

    // --- Dirección ---------------------------------------------------------------------

    @Test
    @DisplayName("dirección: máx 70 caracteres y DEBE contener '#'; 71 sin # se rechaza")
    void direccion() {
        String exact70 = "#" + "a".repeat(69); // 70 chars con '#'
        String sinNumeral = "a".repeat(70); // 70 chars SIN '#'
        String of71 = "#" + "a".repeat(70); // 71 chars
        assertAll(
                () -> assertTrue(PaymentValidator.validateDireccion(exact70).isPresent()),
                () -> assertTrue(PaymentValidator.validateDireccion(sinNumeral).isEmpty(),
                        "70 chars PERO sin '#'"),
                () -> assertTrue(PaymentValidator.validateDireccion(of71).isEmpty(),
                        "71 chars: excede el máximo"),
                () -> assertTrue(PaymentValidator.validateDireccion("Av. Reforma #124, Col. Norte").isPresent()),
                () -> assertTrue(PaymentValidator.validateDireccion("").isEmpty())
        );
    }

    // --- validateForm:拳头 inline mapping -------------------------------------------

    @Test
    @DisplayName("validateForm: vacío cuando todo es válido y con TODAS las claves cuando no")
    void formCompleto_mapaDeErrores() {
        var perfilOk = new com.casl.mp3converter.core.db.CustomerProfile(
                "Ana", "García", "ana@test.com", "5512345678");
        var tarjetaOk = new PaymentRequest("Ana García", "4111111111111111", "12/2099", "123");
        assertTrue(PaymentValidator.validateForm(perfilOk, "Calle #12", tarjetaOk).isEmpty());

        var bad = PaymentValidator.validateForm(
                new com.casl.mp3converter.core.db.CustomerProfile("A", "G1cía", "noemail", "55123456"),
                "calle sin numeral",
                new PaymentRequest("Ana García", "1111", "13/2099", "12"));

        assertAll(
                () -> assertEquals(8, bad.size(), "todos los campos reportados"),
                () -> assertTrue(bad.containsKey(PaymentValidator.FIELD_TARJETA)),
                () -> assertTrue(bad.containsKey(PaymentValidator.FIELD_VENCIMIENTO)),
                () -> assertTrue(bad.containsKey(PaymentValidator.FIELD_CVV))
        );
    }
}
