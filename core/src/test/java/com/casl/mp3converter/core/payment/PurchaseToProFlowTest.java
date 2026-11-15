package com.casl.mp3converter.core.payment;

import com.casl.mp3converter.core.db.ActivationRepository;
import com.casl.mp3converter.core.db.ConnectionProvider;
import com.casl.mp3converter.core.db.CustomerProfile;
import com.casl.mp3converter.core.license.ActivationCodeGenerator;
import com.casl.mp3converter.core.license.FieldCrypto;
import com.casl.mp3converter.core.license.LicenseController;
import com.casl.mp3converter.core.license.LicenseVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flujo compra→pro completo (mapa de pruebas 2.6, tag "integration" del plan):
 * formulario → mock APRUEBA → código generado y persistido CIFRADO → (diálogo)
 * → activación escribe el .lic → estado de licencia PRO.
 * Sin FX: toda la decisión de negocio vive en el core.
 */
@Tag("integration")
@DisplayName("PurchaseToProFlow - formulario -> mock -> código -> licencia (criterio fase 5)")
class PurchaseToProFlowTest {

    private static final CustomerProfile PERFIL =
            new CustomerProfile("Ana Laura", "García", "compra.flow@test.com", "5512345678");
    private static final String DIRECCION = "Av. Reforma #124";
    private static final PaymentRequest TARJETA =
            new PaymentRequest("Ana García", "4111111111111111", "12/2099", "123");

    private ConnectionProvider dbProvider;
    private String previousLicenseProperty;

    @TempDir
    Path licenseDir;

    @BeforeEach
    void setUp() {
        // MODE=PostgreSQL: la MISMA migración V1__init.sql (BYTEA) corre en
        // H2 para tests y en Postgres real (Supabase) en producción:
        String jdbcUrl = "jdbc:h2:mem:flow_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        dbProvider = () -> DriverManager.getConnection(jdbcUrl);

        previousLicenseProperty = System.getProperty("mp3converter.license.path");
        System.setProperty("mp3converter.license.path", licenseDir.toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        if (previousLicenseProperty == null) {
            System.clearProperty("mp3converter.license.path");
        } else {
            System.setProperty("mp3converter.license.path", previousLicenseProperty);
        }
    }

    @Test
    @DisplayName("FORM INVÁLIDO: la pasarela NO se llama y no se persiste nada")
    void formaInvalida_noCobraNiPersiste() throws Exception {
        AtomicInteger gatewayCalls = new AtomicInteger();
        var flow = new PaymentFlow(new CountingGateway(gatewayCalls), new ActivationRepository(dbProvider));

        var outcome = flow.purchase(
                new CustomerProfile("Ana", "García", "correo", "5512"), // teléfono de 4
                "calle sin numeral",
                new PaymentRequest("Ana García", "1111", "13/2099", "12"));

        assertAll(
                () -> assertEquals(PurchaseOutcome.Status.FORM_INVALID, outcome.status()),
                () -> assertEquals(0, gatewayCalls.get(), "la pasarela nunca se llama"),
                () -> assertFalse(outcome.codePersisted()),
                () -> assertNull(outcome.activationCode()),
                () -> assertTrue(outcome.fieldErrors().size() >= 4),
                () -> assertFalse(Files.exists(LicenseVerifier.licenseFile()),
                        "sin compra => sin archivo de licencia")
        );
    }

    @Test
    @DisplayName("PASARELA RECHAZA: sin código, sin licencia")
    void pagoRechazado() throws Exception {
        var flow = new PaymentFlow(new RejectedGateway(), new ActivationRepository(dbProvider));

        var outcome = flow.purchase(PERFIL, DIRECCION, TARJETA);

        assertAll(
                () -> assertEquals(PurchaseOutcome.Status.PAYMENT_REJECTED, outcome.status()),
                () -> assertNull(outcome.activationCode()),
                () -> assertFalse(Files.exists(LicenseVerifier.licenseFile()))
        );
    }

    @Test
    @DisplayName("COMPRA FELIZ: mock aprueba → código persistido cifrado → listo para activar")
    void compraFeliz_persiste() throws Exception {
        var flow = new PaymentFlow(new MockPaymentGateway(0, 0), new ActivationRepository(dbProvider));

        var outcome = flow.purchase(PERFIL, DIRECCION, TARJETA);

        assertAll(
                () -> assertEquals(PurchaseOutcome.Status.PURCHASED, outcome.status()),
                () -> assertTrue(outcome.codePersisted()),
                () -> assertNotNull(outcome.activationCode()),
                () -> assertTrue(ActivationCodeGenerator.isFormatValid(outcome.activationCode())),
                () -> assertTrue(outcome.gatewayReference().startsWith("MOCK-"))
        );

        assertActiveLookup(outcome.activationCode());
    }

    @Test
    @DisplayName("ACTIVACIÓN: LicenseController escribe el .lic y el verificador lee PRO")
    void activacion_escribeLicVerificado() throws Exception {
        var flow = new PaymentFlow(new MockPaymentGateway(0, 0), new ActivationRepository(dbProvider));
        var outcome = flow.purchase(PERFIL, DIRECCION, TARJETA);
        assertEquals(PurchaseOutcome.Status.PURCHASED, outcome.status());

        var state = LicenseController.activate(
                outcome.activationCode(), FieldCrypto.emailHash(PERFIL.correo()));

        assertAll(
                () -> assertEquals(LicenseVerifier.Tier.PRO, state.tier()),
                () -> assertEquals(outcome.activationCode(), state.payload().activationCode()),
                // el archivo en disco no revela nada (requisito no-transparente de la fase 3):
                () -> {
                    String raw = new String(Files.readAllBytes(LicenseVerifier.licenseFile()));
                    assertFalse(raw.contains(outcome.activationCode()), "sin código en claro en el binario");
                    assertFalse(raw.contains("Ana Laura"), "sin nombre en claro en el binario");
                }
        );
    }

    @Test
    @DisplayName("RE-ACTIVACIÓN: el código guardado re-escribe la licencia tras borrarla (reinstalación)")
    void reactivacion_conCodigoGuardado() throws Exception {
        var flow = new PaymentFlow(new MockPaymentGateway(0, 0), new ActivationRepository(dbProvider));
        String savedCode = flow.purchase(PERFIL, DIRECCION, TARJETA).activationCode();

        Files.deleteIfExists(LicenseVerifier.licenseFile());
        assertFalse(Files.exists(LicenseVerifier.licenseFile()));

        var state = LicenseController.activate(savedCode, FieldCrypto.emailHash(PERFIL.correo()));
        assertEquals(LicenseVerifier.Tier.PRO, state.tier());
    }

    @Test
    @DisplayName("BD CAÍDA tras el cobro: código efímero, el flujo NO se cae, activable")
    void baseDeDatosCaida_codigoEfimero() throws Exception {
        var flow = new PaymentFlow(new MockPaymentGateway(0, 0), null); // sin BD

        var outcome = flow.purchase(PERFIL, DIRECCION, TARJETA);

        assertAll(
                () -> assertEquals(PurchaseOutcome.Status.PURCHASED, outcome.status()),
                () -> assertFalse(outcome.codePersisted()),
                () -> assertNotNull(outcome.activationCode()),
                () -> assertTrue(ActivationCodeGenerator.isFormatValid(outcome.activationCode()))
        );
    }

    private void assertActiveLookup(String code) throws Exception {
        var active = new ActivationRepository(dbProvider).lookupActiveCode(code);
        assertTrue(active.isPresent(), "el código persistido es consultable");
        assertTrue(active.get().codeEnc().length > 0);
    }

    /** Gateway que solo cuenta cuántas veces se llamó a la pasarela. */
    private static class CountingGateway implements PaymentGateway {
        private final AtomicInteger calls;

        CountingGateway(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public ChargeResult charge(PaymentRequest request) {
            calls.incrementAndGet();
            return new ChargeResult(true, "MOCK-x", "aprobado");
        }
    }

    private static class RejectedGateway implements PaymentGateway {
        @Override
        public ChargeResult charge(PaymentRequest request) {
            return new ChargeResult(false, "MOCK-rejected", "cobro rechazado (simulado)");
        }
    }
}
