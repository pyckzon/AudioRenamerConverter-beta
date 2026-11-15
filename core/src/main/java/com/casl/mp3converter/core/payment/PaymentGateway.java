package com.casl.mp3converter.core.payment;

/**
 * Contrato de pasarela de pago (requisito 2.5.1): el flujo de compra NO sabe
 * contra qué pasarela corre. El mock aprueba siempre; una implementación real
 * (Stripe/MercadoPago) implementa esta interfaz y es plug-in.
 */
public interface PaymentGateway {

    /**
     * Cobra y devuelve el resultado. La Request NUNCA se persiste:
     * si se loguea, SOLO con {@link PaymentRequest#maskedCard()}.
     */
    ChargeResult charge(PaymentRequest request);
}
