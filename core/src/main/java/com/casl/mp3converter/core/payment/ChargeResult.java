package com.casl.mp3converter.core.payment;

/**
 * Resultado de la llamada a la pasarela (requisito 2.5.1).
 * La implementación real (Stripe/MercadoPago) plug-in a la misma interfaz.
 */
public record ChargeResult(boolean approved, String reference, String message) {
}
