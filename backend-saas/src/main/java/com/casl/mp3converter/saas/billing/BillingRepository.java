package com.casl.mp3converter.saas.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * FASE 8.3 — Persistencia de facturación (schema saas: plans, payments,
 * subscriptions). Sin datos de tarjeta: sólo montos, gateway y estado.
 */
@Repository
public class BillingRepository {

    private final JdbcTemplate jdbc;

    public BillingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- plans -----------------------------------------------------------------

    public Optional<Plan> planByCode(String code) {
        return jdbc.query("""
                        SELECT id, code, price_cents, currency, period, active
                        FROM saas.plans WHERE code = ?""",
                (rs, i) -> new Plan(rs.getLong("id"), rs.getString("code"),
                        rs.getInt("price_cents"), rs.getString("currency"),
                        rs.getString("period"), rs.getBoolean("active")),
                code).stream().findFirst();
    }

    public Optional<Plan> planById(long id) {
        return jdbc.query("""
                        SELECT id, code, price_cents, currency, period, active
                        FROM saas.plans WHERE id = ?""",
                (rs, i) -> new Plan(rs.getLong("id"), rs.getString("code"),
                        rs.getInt("price_cents"), rs.getString("currency"),
                        rs.getString("period"), rs.getBoolean("active")),
                id).stream().findFirst();
    }

    /** Seed idempotente y PORTABLE (sin MERGE de H2 ni ON CONFLICT de Postgres). */
    public void seedPlanIfAbsent(String code, int priceCents, String currency, String period) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM saas.plans WHERE code = ?", Integer.class, code);
        if (n == null || n == 0) {
            jdbc.update("""
                            INSERT INTO saas.plans (code, price_cents, currency, period, active)
                            VALUES (?,?,?,?,TRUE)""",
                    code, priceCents, currency, period);
        }
    }

    // --- payments ---------------------------------------------------------------

    public record PaymentRow(long id, long userId, long planId, String gateway,
                             String externalId, int amountCents, String currency, String status) { }

    public long insertPendingPayment(long userId, long planId, String gateway,
                                     String externalId, int amountCents, String currency) {
        jdbc.update("""
                        INSERT INTO saas.payments (user_id, plan_id, gateway, external_id,
                                                   amount_cents, currency, status)
                        VALUES (?,?,?,?,?,?,'PENDING')""",
                userId, planId, gateway, externalId, amountCents, currency);
        return jdbc.queryForObject(
                "SELECT id FROM saas.payments WHERE external_id = ?", Long.class, externalId);
    }

    public Optional<PaymentRow> paymentByExternalId(String externalId) {
        return jdbc.query("""
                        SELECT id, user_id, plan_id, gateway, external_id,
                               amount_cents, currency, status
                        FROM saas.payments WHERE external_id = ?""",
                (rs, i) -> new PaymentRow(rs.getLong("id"), rs.getLong("user_id"),
                        rs.getLong("plan_id"), rs.getString("gateway"),
                        rs.getString("external_id"), rs.getInt("amount_cents"),
                        rs.getString("currency"), rs.getString("status")),
                externalId).stream().findFirst();
    }

    public void markPayment(long paymentId, String status) {
        jdbc.update("UPDATE saas.payments SET status = ? WHERE id = ?", status, paymentId);
    }

    // --- subscriptions ----------------------------------------------------------

    public record SubscriptionRow(long id, String planCode, String period, String status,
                                  Timestamp startedAt, Timestamp nextBilling) { }

    public Optional<Long> activeSubscriptionId(long userId, long planId) {
        return jdbc.queryForList("""
                        SELECT id FROM saas.subscriptions
                        WHERE user_id = ? AND plan_id = ? AND status = 'ACTIVE'""",
                Long.class, userId, planId).stream().findFirst();
    }

    public void insertSubscription(long userId, long planId, Timestamp nextBilling) {
        jdbc.update("""
                        INSERT INTO saas.subscriptions (user_id, plan_id, status, next_billing)
                        VALUES (?,?,'ACTIVE',?)""", userId, planId, nextBilling);
    }

    public void renewSubscription(long subscriptionId, Timestamp nextBilling) {
        jdbc.update("""
                        UPDATE saas.subscriptions
                        SET next_billing = ?, status = 'ACTIVE' WHERE id = ?""",
                nextBilling, subscriptionId);
    }

    public void cancelSubscription(long userId, long subscriptionId) {
        jdbc.update("""
                        UPDATE saas.subscriptions
                        SET status = 'CANCELED', canceled_at = ?
                        WHERE id = ? AND user_id = ? AND status = 'ACTIVE'""",
                Timestamp.from(Instant.now()), subscriptionId, userId);
    }

    public List<SubscriptionRow> subscriptionsOfUser(long userId) {
        return jdbc.query("""
                        SELECT s.id, p.code AS plan_code, p.period, s.status,
                               s.started_at, s.next_billing
                        FROM saas.subscriptions s JOIN saas.plans p ON p.id = s.plan_id
                        WHERE s.user_id = ? ORDER BY s.id DESC""",
                (rs, i) -> new SubscriptionRow(rs.getLong("id"), rs.getString("plan_code"),
                        rs.getString("period"), rs.getString("status"),
                        rs.getTimestamp("started_at"), rs.getTimestamp("next_billing")),
                userId);
    }
}
