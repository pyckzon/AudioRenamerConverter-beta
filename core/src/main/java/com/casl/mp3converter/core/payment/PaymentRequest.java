package com.casl.mp3converter.core.payment;

/**
 * Solicitud de cobro (requisito 2.5.1). Los datos bancarios VIVEN SOLO EN MEMORIA:
 * son parámetro de {@link PaymentGateway#charge} y NUNCA se persisten ni van a
 * logs sin máscara ({@link #maskedCard()} es la única forma de loguear).
 */
public record PaymentRequest(String cardholder, String cardNumber, String expiryMmYyyy, String cvv) {

    /** Solo los 16 dígitos (sin espacios visuales). */
    public String cardDigits() {
        return cardNumber == null ? "" : cardNumber.replaceAll("\\D", "");
    }

    /** Máscara segura para logs y UI: "**** 1234" — nunca el número completo. */
    public String maskedCard() {
        String digits = cardDigits();
        return digits.length() == 16 ? "**** " + digits.substring(12) : "****";
    }
}
