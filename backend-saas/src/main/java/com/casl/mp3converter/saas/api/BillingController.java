package com.casl.mp3converter.saas.api;

import com.casl.mp3converter.saas.auth.JwtAuthFilter;
import com.casl.mp3converter.saas.billing.BillingRepository;
import com.casl.mp3converter.saas.billing.BillingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * FASE 8.3 — Contrato de facturación (docs/saas/ARCHITECTURE.md §2):
 * GET  /api/subscriptions      -> 200 lista del usuario autenticado (JWT)
 * POST /api/subscriptions      -> 200 cancelar {action:"cancel", subscriptionId}
 * POST /api/payments/checkout  -> 201 sesión de pago (gateway) + PENDING
 * POST /api/payments/webhook   -> 200 confirmación del gateway -> suscripción
 * (webhook público: la firma real del gateway se valida en la fase de integración)
 */
@RestController
@RequestMapping("/api")
public class BillingController {

    private final BillingService billing;

    public BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/subscriptions")
    public List<BillingRepository.SubscriptionRow> subscriptions(HttpServletRequest request) {
        return billing.subscriptionsOf(userId(request));
    }

    public record SubscriptionAction(String action, Long subscriptionId) { }

    @PostMapping("/subscriptions")
    public ResponseEntity<?> action(@RequestBody SubscriptionAction req, HttpServletRequest request) {
        if (req.action() == null || !"cancel".equals(req.action())
                || req.subscriptionId() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "se esperaba {action:\"cancel\", subscriptionId}"));
        }
        var result = billing.cancel(userId(request), req.subscriptionId());
        return result.ok()
                ? ResponseEntity.ok(Map.of("canceled", result.canceledId()))
                : ResponseEntity.status(404).body(Map.of("error", "suscripción no encontrada"));
    }

    @PostMapping("/payments/checkout")
    public ResponseEntity<?> checkout(@RequestBody BillingService.CheckoutRequest req,
                                      HttpServletRequest request) {
        var result = billing.createCheckout(userId(request), req.planCode());
        if (result.ok()) {
            return ResponseEntity.status(201).body(Map.of(
                    "paymentId", result.created().paymentId(),
                    "gateway", result.created().gateway(),
                    "checkoutUrl", result.created().checkoutUrl(),
                    "externalId", result.created().externalId()));
        }
        return ResponseEntity.badRequest().body(Map.of(
                "error", result.error() == BillingService.CheckoutError.UNKNOWN_PLAN
                        ? "plan desconocido" : "plan inactivo"));
    }

    @PostMapping("/payments/webhook")
    public ResponseEntity<?> webhook(@RequestBody BillingService.WebhookRequest req) {
        var result = billing.confirmPayment(req);
        return result.ok()
                ? ResponseEntity.ok(Map.of("subscriptionId", result.subscriptionId(), "status", "ACTIVE"))
                : switch (result.error()) {
                    case UNKNOWN_PAYMENT -> ResponseEntity.status(404)
                            .body(Map.of("error", "pago no encontrado"));
                    case DUPLICATE -> ResponseEntity.status(200)
                            .body(Map.of("status", "IDEMPOTENTE", "subscriptionId", result.subscriptionId()));
                    case UNEXPECTED_STATUS -> ResponseEntity.badRequest()
                            .body(Map.of("error", "estado de pago inesperado"));
                };
    }

    private static long userId(HttpServletRequest request) {
        Object attr = request.getAttribute(JwtAuthFilter.USER_ID_ATTR);
        if (attr instanceof Long id) {
            return id;
        }
        throw new IllegalStateException("endpoint protegido sin userId (filtro JWT ausente)");
    }
}
