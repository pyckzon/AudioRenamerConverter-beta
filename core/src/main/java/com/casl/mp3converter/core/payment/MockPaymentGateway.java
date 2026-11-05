package com.casl.mp3converter.core.payment;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.logging.Logger;

/**
 * Pasarela MOCK del portfolio (requisito 2.5.1): APRUEBA SIEMPRE, con latencia
 * simulada y log seguro. NUNCA registra el número completo de tarjeta:
 * solo la máscara {@code **** 1234} (probado en MockPaymentGatewayTest).
 */
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger LOGGER = Logger.getLogger(MockPaymentGateway.class.getName());

    /** Rango de latencia simulada en ms (experiencia realista en el demo). */
    private final int minLatencyMs;
    private final int maxLatencyMs;
    private final SecureRandom random = new SecureRandom();

    public MockPaymentGateway() {
        this(200, 800);
    }

    MockPaymentGateway(int minLatencyMs, int maxLatencyMs) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
    }

    @Override
    public ChargeResult charge(PaymentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request requerido");
        }
        simulateLatency();

        String reference = "MOCK-" + HexFormat.of().formatHex(randomNumber());
        // LOG SEGURO: nunca el número completo (requisito 2.5.1):
        LOGGER.info(() -> "Cobro simulado APROBADO: tarjeta=" + request.maskedCard()
                + " referencia=" + reference);
        return new ChargeResult(true, reference, "Pago aprobado (pasarela mock).");
    }

    private byte[] randomNumber() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return bytes;
    }

    private void simulateLatency() {
        int latency = maxLatencyMs > minLatencyMs
                ? minLatencyMs + random.nextInt(maxLatencyMs - minLatencyMs + 1)
                : minLatencyMs;
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
