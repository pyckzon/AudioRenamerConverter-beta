package com.casl.mp3converter.saas.billing;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * FASE 8.3 — Gateway MOCK (aprueba en el webhook simulado, igual filosofía que
 * MockPaymentGateway de v2). La URL de checkout es local-ficticia: no cobra
 * nada y jamás toca datos de tarjeta (esos viven SOLO en la web del gateway real).
 */
@Component
@Primary
public class MockCheckoutGateway implements PaymentGatewaySaaS {

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public CheckoutSession createCheckout(long userId, Plan plan) {
        String externalId = "MOCK-" + UUID.randomUUID();
        String url = "https://checkout.mock.local/pay/" + externalId;
        return new CheckoutSession(externalId, url);
    }
}
