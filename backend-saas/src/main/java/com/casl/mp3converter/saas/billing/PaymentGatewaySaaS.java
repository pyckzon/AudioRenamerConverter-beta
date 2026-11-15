package com.casl.mp3converter.saas.billing;

/**
 * FASE 8.3 — Costura de pasarela (análoga a PaymentGateway de v2):
 * la app NUNCA ve la tarjeta — el checkout ocurre en la web del gateway
 * y este backend sólo crea la sesión y recibe el webhook de confirmación.
 * La implementación real (Stripe/MercadoPago) entra plug-in a esta interfaz.
 */
public interface PaymentGatewaySaaS {

    /** Nombre que se persiste en saas.payments.gateway. */
    String name();

    /** Crea la sesión de pago y devuelve su id externo + URL de checkout web. */
    CheckoutSession createCheckout(long userId, Plan plan);

    /** Sesión de checkout creada por el gateway. */
    record CheckoutSession(String externalId, String checkoutUrl) { }
}
