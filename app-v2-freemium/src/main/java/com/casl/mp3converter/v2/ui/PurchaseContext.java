package com.casl.mp3converter.v2.ui;

import com.casl.mp3converter.core.db.ActivationRepository;
import com.casl.mp3converter.core.db.ConnectionProvider;
import com.casl.mp3converter.core.db.CustomerProfile;
import com.casl.mp3converter.core.db.DatabaseConfig;
import com.casl.mp3converter.core.payment.MockPaymentGateway;
import com.casl.mp3converter.core.payment.PaymentFlow;
import com.casl.mp3converter.core.payment.PurchaseOutcome;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cableado del flujo de compra de la app v2 (fase 5): gateway MockPaymentGateway
 * + repositorio de activaciones SOLO si las credenciales de Supabase están
 * presentes (modo demo local con código efímero cuando no lo están).
 */
final class PurchaseContext {

    private static final Logger LOGGER = Logger.getLogger(PurchaseContext.class.getName());

    private PurchaseContext() {
    }

    /** Pasarela + repositorio listos para usar; nunca lanza (degrada a sin-BD). */
    static PaymentFlow createFlow() {
        return new PaymentFlow(new MockPaymentGateway(), activationRepository());
    }

    /** null cuando no hay credenciales: el flujo genera código efímero (demo). */
    static ActivationRepository activationRepository() {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        if (config == null) {
            return null;
        }
        try {
            // el proveedor se construye DENTRO del core (sin tipos SQL en la app):
            ConnectionProvider provider = config.connectionProvider();
            return new ActivationRepository(provider);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "No se pudo preparar la conexión a Supabase: modo demo", e);
            return null;
        }
    }

    /** Perfil con el prefijo del dropdown incorporado: "+525512345678". */
    static CustomerProfile profileFrom(String nombre, String apellido, String correo,
                                       String countryCode, String phoneDigits10) {
        return new CustomerProfile(nombre.strip(), apellido.strip(),
                correo.strip().toLowerCase(java.util.Locale.ROOT),
                countryCode + phoneDigits10);
    }
}
