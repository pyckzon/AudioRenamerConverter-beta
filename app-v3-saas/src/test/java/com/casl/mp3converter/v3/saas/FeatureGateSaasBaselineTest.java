package com.casl.mp3converter.v3.saas;

import com.casl.mp3converter.core.Mp3ConversionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fase 9.1 — Baseline del FeatureGate de v3: arranca FREE, el branding es
 * SaaS (3.0.0) y el flip en caliente funciona como en v2. La conexión real
 * al backend (suscripción/heartbeat) se prueba en 9.2+.
 */
@DisplayName("Fase 9.1: FeatureGate v3 baseline (FREE por defecto, branding SaaS)")
class FeatureGateSaasBaselineTest {

    @AfterEach
    void reset() {
        FeatureGate.setProEnabled(false);
    }

    @Test
    @DisplayName("arranque FREE con branding 3.0.0 y reglas del free tier intactas")
    void baseline() {
        FeatureGate.refreshAtStartup();
        assertAll(
                () -> assertFalse(FeatureGate.isPro(), "sin suscripción verificada: FREE"),
                () -> assertTrue(FeatureGate.headerTitle().contains("gratuita")),
                () -> assertTrue(FeatureGate.footer().contains("3.0.0")),
                () -> assertTrue(FeatureGate.footer().contains("92 kbps")),
                () -> assertTrue(FeatureGate.buyTitle().contains("Suscribirse"))
        );
    }

    @Test
    @DisplayName("hot flip PRO: headers/footer cambian y el servicio es full VBR")
    void hotFlip() {
        FeatureGate.setProEnabled(true);
        assertAll(
                () -> assertTrue(FeatureGate.isPro()),
                () -> assertTrue(FeatureGate.headerTitle().contains("PRO")),
                () -> assertTrue(FeatureGate.footer().contains("suscripción activa"))
        );
        Mp3ConversionService service = FeatureGate.createService();
        assertNotNull(service, "el servicio Pro se construye sin watermark ni bloqueos");
    }
}
