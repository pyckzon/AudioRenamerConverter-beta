package com.casl.mp3converter.core.payment;

import com.casl.mp3converter.core.db.CustomerProfile;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validación EXACTA del formulario de pago (requisito 2.5):
 * <ul>
 *   <li>Nombre/Apellido: solo letras (acentos/ñ incluidas), mín. 2, sin dígitos/símbolos.</li>
 *   <li>Correo: regex RFC-5322 simplificada; se devuelve normalizado (trim+lowercase).</li>
 *   <li>Teléfono: 10 dígitos; el prefijo de país es dropdown con default +52 (México).</li>
 *   <li>Dirección: máx. 70 caracteres y DEBE contener un '#';</li>
 *   <li>Tarjeta: 16 dígitos (con espacios visuales, se limpien) + Luhn.</li>
 *   <li>Vencimiento: mm/AAAA válido y NO expirado.</li>
 *   <li>CVV: 3 dígitos.</li>
 * </ul>
 * Cada devolución ES el mensaje inline por campo (criterio 2.5: "Mensaje de error
 * inline específico por campo; botón Pagar habilitado solo cuando todo es válido").
 */
public final class PaymentValidator {

    public static final int ADDRESS_MAX_LENGTH = 70;

    /** Letras Unicode (acentos + ñ), espacios entre palabras; sin dígitos ni símbolos. */
    public static final Pattern NAME_PATTERN = Pattern.compile("\\p{L}+( \\p{L}+)*");
    public static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    /** mm/AAAA: mes 01..12, año de 4 dígitos. */
    public static final Pattern EXPIRY_PATTERN = Pattern.compile("^(0[1-9]|1[0-2])[/-]\\d{4}$");

    /**
     * Prefijos de país válidos del dropdown (+52 default México; misma lista que
     * la UI): se aceptan antepuestos al número local de 10 dígitos.
     */
    public static final java.util.List<String> COUNTRY_PREFIXES =
            java.util.List.of("52", "1", "34", "54", "57", "51", "56");

    private PaymentValidator() {
    }

    // --- Persona (2.5) ---------------------------------------------------------

    public static Optional<String> validateNombre(String nombre) {
        return validPersonName(nombre);
    }

    public static Optional<String> validateApellido(String apellido) {
        return validPersonName(apellido);
    }

    /** Devuelve el correo NORMALIZADO si es válido; Optional vacío si no. */
    public static Optional<String> validOrNormalizeEmail(String correo) {
        String v = safe(correo);
        if (!EMAIL_PATTERN.matcher(v).matches()) {
            return Optional.empty();
        }
        return Optional.of(v.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * El teléfono SOLO dígitos, exactamente 10 (el prefijo viene del dropdown).
     * También acepta el formato E.164 que guarda el perfil: prefijo + 10 dígitos
     * (p. ej. "525512345678" con el dropdown de +52 México).
     */
    public static Optional<String> validatePhoneDigits(String telefono) {
        String digits = digitsOnly(telefono);
        if (digits.length() == 10) {
            return Optional.of(digits);
        }
        for (String prefix : COUNTRY_PREFIXES) {
            if (digits.startsWith(prefix) && digits.length() == prefix.length() + 10) {
                return Optional.of(digits);
            }
        }
        return Optional.empty();
    }

    /** Dirección: no vacía, máx 70 y debe contener '#'. */
    public static Optional<String> validateDireccion(String direccion) {
        String v = safe(direccion);
        if (v.isEmpty()) {
            return Optional.empty();
        }
        if (v.length() > ADDRESS_MAX_LENGTH || !v.contains("#")) {
            return Optional.empty();
        }
        return Optional.of(v);
    }

    // --- Tarjeta (2.5) ---------------------------------------------------------

    /** 16 dígitos + algoritmo de Luhn (se limpian los espacios visuales del formato dinámico). */
    public static Optional<String> validateCardNumber(String cardNumber) {
        String digits = digitsOnly(cardNumber);
        if (digits.length() != 16 || !digits.matches("\\d{16}") || !luhnValid(digits)) {
            return Optional.empty();
        }
        return Optional.of(digits);
    }

    /** Vencimiento mm/AAAA (también acepta el separador '/' o '-'); NO expirado. */
    public static Optional<String> validateExpiry(String expiry) {
        String v = safe(expiry);
        if (!EXPIRY_PATTERN.matcher(v).matches()) {
            return Optional.empty();
        }
        int month = Integer.parseInt(v.substring(0, 2));
        int year = Integer.parseInt(v.substring(v.length() - 4));
        YearMonth requested = YearMonth.of(year, month);
        if (requested.isBefore(YearMonth.now(java.time.ZoneId.systemDefault()))) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT, "%02d/%04d", month, year));
    }

    /** CVV: exactamente 3 dígitos. */
    public static Optional<String> validateCvv(String cvv) {
        return digitsOnly(cvv).length() == 3 ? Optional.of(digitsOnly(cvv)) : Optional.empty();
    }

    // --- Luhn ------------------------------------------------------------------

    /** Algoritmo de Luhn (ISO/IEC 7812-1): checksum de la tarjeta. */
    public static boolean luhnValid(String digits) {
        if (digits == null || digits.length() < 2) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = true; // el dígito más a la derecha no se duplica; empezamos dos posiciones antes
        for (int i = digits.length() - 2; i >= 0; i--) {
            int value = digits.charAt(i) - '0';
            if (doubleIt) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            sum += value;
            doubleIt = !doubleIt;
        }
        int lastDigit = digits.charAt(digits.length() - 1) - '0';
        return (sum + lastDigit) % 10 == 0;
    }

    // --- Validación completa del formulario ------------------------------------

    /** Claves de los mensajes por campo (enlazan con los Labels inline de la UI). */
    public static final String FIELD_NOMBRE = "nombre";
    public static final String FIELD_APELLIDO = "apellido";
    public static final String FIELD_CORREO = "correo";
    public static final String FIELD_TELEFONO = "telefono";
    public static final String FIELD_DIRECCION = "direccion";
    public static final String FIELD_TARJETA = "tarjeta";
    public static final String FIELD_VENCIMIENTO = "vencimiento";
    public static final String FIELD_CVV = "cvv";

    /**
     * Valida TODO el formulario (persona + tarjeta). Devuelve SOLO los errores
     * (LinkedHashMap para el orden estable de la UI): vacío = todo válido.
     */
    public static Map<String, String> validateForm(CustomerProfile profile, String direccion,
                                                   PaymentRequest payment) {
        Map<String, String> errors = new LinkedHashMap<>();

        validateNombre(profile.nombre())
                .ifPresentOrElse(x -> { }, () -> errors.put(FIELD_NOMBRE,
                        "El nombre admite solo letras (mínimo 2) sin números ni símbolos."));
        validateApellido(profile.apellido())
                .ifPresentOrElse(x -> { }, () -> errors.put(FIELD_APELLIDO,
                        "El apellido admite solo letras (mínimo 2) sin números ni símbolos."));
        validOrNormalizeEmail(profile.correo())
                .ifPresentOrElse(x -> { }, () -> errors.put(FIELD_CORREO, "Correo electrónico no válido."));
        validatePhoneDigits(profile.telefono())
                .ifPresentOrElse(x -> { }, () -> errors.put(FIELD_TELEFONO,
                        "El teléfono admite exactamente 10 dígitos."));
        validateDireccion(direccion)
                .ifPresentOrElse(x -> { }, () -> errors.put(FIELD_DIRECCION,
                        "Dirección de máximo 70 caracteres y debe contener un '#'."));
        validateCardNumber(payment.cardNumber())
                .ifPresentOrElse(x -> { }, () -> errors.put(FIELD_TARJETA,
                        "La tarjeta requiere 16 dígitos válidos (Luhn)."));
        validateExpiry(payment.expiryMmYyyy())
                .ifPresentOrElse(x -> { }, () -> errors.put(FIELD_VENCIMIENTO,
                        "Vencimiento mm/AAAA válido y no expirado."));
        validateCvv(payment.cvv())
                .ifPresentOrElse(x -> { }, () -> errors.put(FIELD_CVV, "El CVV son 3 dígitos."));

        return errors;
    }

    // --- helpers ---------------------------------------------------------------

    private static Optional<String> validPersonName(String value) {
        String v = safe(value);
        if (!NAME_PATTERN.matcher(v).matches() || v.length() < 2) {
            return Optional.empty();
        }
        return Optional.of(v);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }
}
