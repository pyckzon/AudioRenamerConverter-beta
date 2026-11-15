package com.casl.mp3converter.saas.billing;

/**
 * FASE 8.3 — Plan de suscripción (fila de saas.plans).
 */
public record Plan(long id, String code, int priceCents, String currency, String period, boolean active) {

    public boolean isMonthly() {
        return "monthly".equals(period);
    }

    public boolean isAnnual() {
        return "annual".equals(period);
    }
}
