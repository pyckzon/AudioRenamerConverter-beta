package com.casl.mp3converter.core.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapa de pruebas 2.5.1 del plan: el mock Aprueba SIEMPRE y, en TODOS los logs,
 * el número completo de tarjeta JAMÁS aparece (solo máscara "**** 1234").
 */
@DisplayName("MockPaymentGateway - siempre aprueba y loguea solo máscara (requisito 2.5.1)")
class MockPaymentGatewayTest {

    private static final String FULL_CARD = "4111111111111111";
    private static final String MASK = "**** 1111";

    /** Captura los logrecords de la pasarela durante la llamada. */
    private List<LogRecord> captureLogs(Runnable action) {
        Logger logger = Logger.getLogger(MockPaymentGateway.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    @Test
    @DisplayName("aprueba SIEMPRE, con referencia y mensaje")
    void siempreAprueba() {
        var gateway = new MockPaymentGateway(0, 0); // sin latencia para el test
        for (int i = 0; i < 5; i++) {
            var result = gateway.charge(request());
            assertAll(
                    () -> assertTrue(result.approved()),
                    () -> assertTrue(result.reference().startsWith("MOCK-")),
                    () -> assertEquals("Pago aprobado (pasarela mock).", result.message())
            );
        }
    }

    @Test
    @DisplayName("los logs NUNCA contienen el número completo, SOLO la máscara **** 1111")
    void logsConMascara() {
        var gateway = new MockPaymentGateway(0, 0);
        List<LogRecord> logs = captureLogs(() -> gateway.charge(request()));

        assertAll(
                () -> assertTrue(!logs.isEmpty(), "hubo al menos un log"),
                () -> logs.forEach(record -> {
                    String text = record.getMessage();
                    assertTrue(!text.contains(FULL_CARD),
                            "el log jamás contiene la tarjeta completa: " + text);
                    assertTrue(text.contains(MASK), "el log muestra la máscara: " + text);
                }),
                () -> assertTrue(logs.stream().mapToLong(r -> r.getMillis()).sorted()
                        .noneMatch(x -> x < 0))
        );
    }

    @Test
    @DisplayName("los datos de tarjeta viven SOLO en el request en memoria: el resultado no los contiene")
    void resultadoSinTarjeta() {
        var gateway = new MockPaymentGateway(0, 0);
        var result = gateway.charge(request());
        String resultToString = String.valueOf(result);
        assertTrue(!resultToString.contains(FULL_CARD),
                "el ChargeResult jamás incluye los datos bancarios");
        assertTrue(!resultToString.contains("4111"), "ni siquiera con espacios");
    }

    @Test
    @DisplayName("nivel INFO para el cobro aprobado (auditable)")
    void logNivelInfo() {
        var gateway = new MockPaymentGateway(0, 0);
        List<LogRecord> logs = captureLogs(() -> gateway.charge(request()));

        assertEquals(Level.INFO, logs.get(0).getLevel());
    }

    private static PaymentRequest request() {
        return new PaymentRequest("Ana García", FULL_CARD, "12/2099", "123");
    }
}
