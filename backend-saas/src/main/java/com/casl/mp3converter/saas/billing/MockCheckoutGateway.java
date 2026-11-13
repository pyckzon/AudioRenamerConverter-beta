package com.casl.mp3converter.saas.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * FASE 8.3 — Gateway MOCK (aprueba en el webhook simulado, igual filosofía que
 * MockPaymentGateway de v2). La URL de checkout apunta a la PÁGINA MOCK del
 * propio backend ({@code GET /mock-checkout/{externalId}}, Fase 9 manual):
 * el usuario "paga" ahí y la página dispara el webhook — EXACTAMENTE el
 * comportamiento de Stripe/MercadoPago. Jamás toca datos de tarjeta (esos
 * viven SOLO en la web del gateway real).
 */
@Component
@Primary
public class MockCheckoutGateway implements PaymentGatewaySaaS {

    /** URL pública del backend (deploy/túnel: sobreescribir saas.public.url). */
    private final String publicUrl;

    public MockCheckoutGateway(
            @Value("${saas.public.url:http://localhost:${server.port:8080}}")
            String publicUrl) {
        this.publicUrl = publicUrl;
    }

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public CheckoutSession createCheckout(long userId, Plan plan) {
        String externalId = "MOCK-" + UUID.randomUUID();
        String url = publicUrl + "/mock-checkout/" + externalId;
        return new CheckoutSession(externalId, url);
    }
}
