package com.casl.mp3converter.saas.billing;

import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * FASE 8.3 — Orquestación de facturación: checkout (PENDING) → webhook del
 * gateway (PAID) → suscripción ACTIVE con next_billing por periodo. Un pago
 * repetido del mismo plan RENUEVA (extiende next_billing) en vez de duplicar.
 */
@Service
public class BillingService {

    private final BillingRepository repo;
    private final PaymentGatewaySaaS gateway;

    public BillingService(BillingRepository repo, PaymentGatewaySaaS gateway) {
        this.repo = repo;
        this.gateway = gateway;
    }

    public record CheckoutRequest(String planCode) { }

    public record CheckoutCreated(long paymentId, String gateway, String checkoutUrl, String externalId) { }

    public enum CheckoutError { UNKNOWN_PLAN, PLAN_INACTIVE }

    public record CheckoutResult(CheckoutCreated created, CheckoutError error) {
        public boolean ok() { return error == null; }
    }

    /** POST /api/payments/checkout: crea la sesión en el gateway + pago PENDING. */
    public CheckoutResult createCheckout(long userId, String planCode) {
        Optional<Plan> plan = repo.planByCode(planCode == null ? "" : planCode.trim());
        if (plan.isEmpty()) {
            return new CheckoutResult(null, CheckoutError.UNKNOWN_PLAN);
        }
        if (!plan.get().active()) {
            return new CheckoutResult(null, CheckoutError.PLAN_INACTIVE);
        }
        var session = gateway.createCheckout(userId, plan.get());
        long paymentId = repo.insertPendingPayment(userId, plan.get().id(),
                gateway.name(), session.externalId(), plan.get().priceCents(), plan.get().currency());
        return new CheckoutResult(new CheckoutCreated(paymentId, gateway.name(),
                session.checkoutUrl(), session.externalId()), null);
    }

    public record WebhookRequest(String externalId, String status) { }

    public enum WebhookError { UNKNOWN_PAYMENT, DUPLICATE, UNEXPECTED_STATUS }

    public record WebhookResult(long subscriptionId, WebhookError error) {
        public boolean ok() { return error == null; }
    }

    /**
     * Webhook simulado/real: confirma el pago y activa (o renueva) la suscripción.
     * El idempotente falla elegante: confirmar dos veces el mismo external_id
     * NO duplica suscripciones (DUPLICATE).
     */
    public WebhookResult confirmPayment(WebhookRequest req) {
        if (req.externalId() == null || req.status() == null) {
            return new WebhookResult(0, WebhookError.UNEXPECTED_STATUS);
        }
        Optional<BillingRepository.PaymentRow> payment =
                repo.paymentByExternalId(req.externalId().trim());
        if (payment.isEmpty()) {
            return new WebhookResult(0, WebhookError.UNKNOWN_PAYMENT);
        }
        var p = payment.get();
        if (!"PAID".equalsIgnoreCase(req.status())) {
            repo.markPayment(p.id(), "FAILED");
            return new WebhookResult(0, WebhookError.UNEXPECTED_STATUS);
        }
        if (!"PENDING".equals(p.status())) {
            return new WebhookResult(0, WebhookError.DUPLICATE);
        }

        Optional<Plan> plan = repo.planById(p.planId());
        if (plan.isEmpty()) {
            return new WebhookResult(0, WebhookError.UNEXPECTED_STATUS);
        }
        Timestamp nextBilling = nextBillingFrom(plan.get());
        Optional<Long> existing = repo.activeSubscriptionId(p.userId(), p.planId());
        if (existing.isPresent()) {
            repo.renewSubscription(existing.get(), nextBilling);
            repo.markPayment(p.id(), "PAID");
            return new WebhookResult(existing.get(), null);
        }
        repo.insertSubscription(p.userId(), p.planId(), nextBilling);
        repo.markPayment(p.id(), "PAID");
        // el id recién insertado:
        Long subId = repo.activeSubscriptionId(p.userId(), p.planId()).orElse(0L);
        return new WebhookResult(subId, null);
    }

    public List<BillingRepository.SubscriptionRow> subscriptionsOf(long userId) {
        return repo.subscriptionsOfUser(userId);
    }

    public enum CancelError { NOT_FOUND }

    public record CancelResult(long canceledId, CancelError error) {
        public boolean ok() { return error == null; }
    }

    /** POST /api/subscriptions {action:"cancel", subscriptionId}: baja en caliente. */
    public CancelResult cancel(long userId, long subscriptionId) {
        var all = repo.subscriptionsOfUser(userId).stream()
                .filter(s -> s.id() == subscriptionId && "ACTIVE".equals(s.status()))
                .toList();
        if (all.isEmpty()) {
            return new CancelResult(0, CancelError.NOT_FOUND);
        }
        repo.cancelSubscription(userId, subscriptionId);
        return new CancelResult(subscriptionId, null);
    }

    /** next_billing = ahora + 1 mes (monthly) o + 1 año (annual), en UTC. */
    static Timestamp nextBillingFrom(Plan plan) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime next = plan.isAnnual() ? now.plusYears(1) : now.plusMonths(1);
        return Timestamp.from(next.toInstant());
    }
}
