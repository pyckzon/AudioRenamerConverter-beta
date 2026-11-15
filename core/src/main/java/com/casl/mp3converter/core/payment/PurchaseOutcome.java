package com.casl.mp3converter.core.payment;

import com.casl.mp3converter.core.db.CustomerProfile;
import com.casl.mp3converter.core.license.LicenseVerifier;

import java.util.Map;

/** Resultado del flujo completo "formulario → pago → código" (criterio de la fase 5). */
public record PurchaseOutcome(
        Status status,
        Map<String, String> fieldErrors,
        /** Perfil tal cual se escribió; SOLO en memoria (nunca llega a logs ni a disco). */
        CustomerProfile profile,
        String activationCode,
        boolean codePersisted,
        String gatewayReference) {

    public enum Status {
        /** Formulario inválido: NO se llama a la pasarela ni se persiste nada. */
        FORM_INVALID,
        /** La pasarela rechazó el cobro: no hay código ni persistencia. */
        PAYMENT_REJECTED,
        /** Cobro aprobado: código generado (y persistido si hay BD), listo para activar. */
        PURCHASED
    }

    public static PurchaseOutcome invalid(Map<String, String> fieldErrors, CustomerProfile profile) {
        return new PurchaseOutcome(Status.FORM_INVALID, fieldErrors, profile, null, false, null);
    }

    public static PurchaseOutcome rejected(ChargeResult charge, CustomerProfile profile) {
        return new PurchaseOutcome(Status.PAYMENT_REJECTED, Map.of(), profile, null, false,
                charge.reference());
    }

    public static PurchaseOutcome purchased(CustomerProfile profile, String code,
                                            boolean persisted, String gatewayReference) {
        return new PurchaseOutcome(Status.PURCHASED, Map.of(), profile, code, persisted,
                gatewayReference);
    }

    public boolean succeeded() {
        return status == Status.PURCHASED;
    }
}
