package com.casl.mp3converter.saas.license;

import com.casl.mp3converter.core.license.FieldCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * FASE 8.4 — Persistencia de tokens de licencia (saas.license_tokens).
 * El token NUNCA se guarda en claro: sólo su HMAC con el secreto embebido
 * (FieldCrypto.codeHash, el mismo mecanismo de los códigos de v2).
 */
@Repository
public class LicenseTokenRepository {

    private final JdbcTemplate jdbc;

    public LicenseTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasActiveSubscription(long userId) {
        Integer n = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM saas.subscriptions
                        WHERE user_id = ? AND status = 'ACTIVE'""",
                Integer.class, userId);
        return n != null && n > 0;
    }

    public Optional<Long> activeSubscriptionId(long userId) {
        return jdbc.queryForList("""
                        SELECT id FROM saas.subscriptions
                        WHERE user_id = ? AND status = 'ACTIVE'
                        ORDER BY id DESC LIMIT 1""",
                Long.class, userId).stream().findFirst();
    }

    /** UPSERT del token de licencia (hash único por usuario/subscription vigente). */
    public void upsert(long userId, long subscriptionId, String tokenHash,
                       Timestamp expiresAt) {
        List<Long> existing = jdbc.queryForList("""
                        SELECT id FROM saas.license_tokens
                        WHERE user_id = ? AND subscription_id = ?""",
                Long.class, userId, subscriptionId);
        if (existing.isEmpty()) {
            jdbc.update("""
                            INSERT INTO saas.license_tokens
                                (user_id, subscription_id, token_hash, expires_at)
                            VALUES (?,?,?,?)""",
                    userId, subscriptionId, tokenHash, expiresAt);
        } else {
            jdbc.update("""
                            UPDATE saas.license_tokens
                            SET token_hash = ?, expires_at = ?, last_heartbeat = NULL
                            WHERE id = ?""",
                    tokenHash, expiresAt, existing.getFirst());
        }
    }

    public record TokenRow(long id, long userId, long subscriptionId,
                           Instant expiresAt, Instant lastHeartbeat) { }

    public Optional<TokenRow> byTokenHash(String tokenHash) {
        return jdbc.query("""
                        SELECT id, user_id, subscription_id, expires_at, last_heartbeat
                        FROM saas.license_tokens WHERE token_hash = ?""",
                (rs, i) -> new TokenRow(rs.getLong("id"), rs.getLong("user_id"),
                        rs.getLong("subscription_id"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("last_heartbeat") == null
                                ? null : rs.getTimestamp("last_heartbeat").toInstant()),
                tokenHash).stream().findFirst();
    }

    public String subscriptionStatus(long subscriptionId) {
        return jdbc.queryForObject(
                "SELECT status FROM saas.subscriptions WHERE id = ?",
                String.class, subscriptionId);
    }

    /** Heartbeat exitoso: renueva la ventana de gracia (now + 7 días). */
    public void touchHeartbeat(long tokenId, Timestamp newExpiry) {
        jdbc.update("""
                        UPDATE saas.license_tokens
                        SET last_heartbeat = ?, expires_at = ?
                        WHERE id = ?""",
                Timestamp.from(Instant.now()), newExpiry, tokenId);
    }
}
