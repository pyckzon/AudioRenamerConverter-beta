package com.casl.mp3converter.core.payment;

import com.casl.mp3converter.core.license.ActivationCodeGenerator;
import com.casl.mp3converter.core.db.ActivationRepository;
import com.casl.mp3converter.core.db.CustomerProfile;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ORQUESTADOR del flujo de compra (criterio fase 5): formulario -> pasarela ->
 * código cifrado en BD -> (la UI muestra el diálogo de activación).
 * <p>
 * Orden verificado por tests:
 * <ol>
 *   <li>Formulario inválido => FORM_INVALID; NO se cobra ni persiste nada.</li>
 *   <li>Pasarela rechaza => PAYMENT_REJECTED; nada se persiste.</li>
 *   <li>Pasarela aprueba => código (persistido en BD con AES+HMAC, o efímero si la
 *       DB no está configurada: modo demo offline del portfolio, documentado) y el
 *       usuario decide activar con el botón "activar contrato Pro" (LicenseController
 *       escribe el .lic y disponible ya en fase 3).</li>
 * </ol>
 * Regla de seguridad: la tarjeta nunca sale de este objeto hacia la BD ni logs
 * (solo el gateway la ve, y él la loguea con máscara, probado).
 */
public class PaymentFlow {

    private static final Logger LOGGER = Logger.getLogger(PaymentFlow.class.getName());

    private final PaymentGateway gateway;
    /** null = la base de datos NO está configurada: modo demo local (código efímero). */
    private final ActivationRepository activations;

    public PaymentFlow(PaymentGateway gateway, ActivationRepository activations) {
        this.gateway = gateway;
        this.activations = activations;
    }

    public PurchaseOutcome purchase(CustomerProfile profile, String direccion, PaymentRequest payment) {
        var fieldErrors = PaymentValidator.validateForm(profile, direccion, payment);
        if (!fieldErrors.isEmpty()) {
            return PurchaseOutcome.invalid(fieldErrors, profile);
        }

        ChargeResult charge = gateway.charge(payment);
        if (!charge.approved()) {
            return PurchaseOutcome.rejected(charge, profile);
        }

        try {
            if (activations != null) {
                var emitted = activations.emitCode(profile, "PRO");
                return PurchaseOutcome.purchased(profile, emitted.code(), true, charge.reference());
            }
            // modo demo sin Supabase: código LOCAL, no persistido:
            LOGGER.warning("Sin Supabase configurado: código de activación efímero (modo demo).");
            return PurchaseOutcome.purchased(profile, ActivationCodeGenerator.generate(), false,
                    charge.reference());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "BD falló tras el cobro: código efímero (demo)", e);
            return PurchaseOutcome.purchased(profile, ActivationCodeGenerator.generate(), false,
                    charge.reference());
        }
    }
}
