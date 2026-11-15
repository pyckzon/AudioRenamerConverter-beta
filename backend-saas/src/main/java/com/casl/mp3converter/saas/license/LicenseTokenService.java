package com.casl.mp3converter.saas.license;

import com.casl.mp3converter.core.license.FieldCrypto;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * FASE 8.4 — Ciclo de vida del token de licencia (docs/saas/ARCHITECTURE.md §4):
 * <ul>
 *   <li><b>issue</b>: con suscripción ACTIVE se emite token (24 bytes aleatorios,
 *       base64url); en BD sólo su HMAC. Válido offline 7 días.</li>
 *   <li><b>heartbeat</b>: la app se presenta con su token; si la suscripción
 *       sigue ACTIVE, la ventana de gracia se renueva (now + 7 días).</li>
 *   <li>Suscripción cancelada/expirada → INACTIVE (la app vuelve a FREE en
 *       caliente, igual que al borrar la .lic de v2).</li>
 *   <li>Token vencido (>7 días sin heartbeat) → EXPIRED → también FREE.</li>
 * </ul>
 */
@Service
public class LicenseTokenService {

    /** ventana de gracia offline del requisito del plan. */
    public static final Duration GRACE = Duration.ofDays(7);

    private final LicenseTokenRepository repo;
    private final SecureRandom random = new SecureRandom();

    public LicenseTokenService(LicenseTokenRepository repo) {
        this.repo = repo;
    }

    public enum IssueError { NO_ACTIVE_SUBSCRIPTION }

    public record IssuedToken(String token, Instant expiresAt) { }

    public record IssueResult(IssuedToken token, IssueError error) {
        public boolean ok() { return error == null; }
    }

    /** POST /api/license/issue (JWT): emite (o rota) el token de licencia. */
    public IssueResult issue(long userId) {
        if (!repo.hasActiveSubscription(userId)) {
            return new IssueResult(null, IssueError.NO_ACTIVE_SUBSCRIPTION);
        }
        long subscriptionId = repo.activeSubscriptionId(userId).orElseThrow();
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now().plus(GRACE);
        repo.upsert(userId, subscriptionId, FieldCrypto.codeHash(token),
                Timestamp.from(expiresAt));
        return new IssueResult(new IssuedToken(token, expiresAt), null);
    }

    public enum HeartbeatStatus { ACTIVE, INACTIVE, EXPIRED }

    public record HeartbeatResult(HeartbeatStatus status, Instant graceUntil) { }

    /**
     * POST /api/license/heartbeat (autenticado con el TOKEN, no JWT):
     * token desconocido → EXPIRED con gracia cero (la app debe re-activar).
     */
    public HeartbeatResult heartbeat(String token) {
        if (token == null || token.isBlank()) {
            return new HeartbeatResult(HeartbeatStatus.EXPIRED, null);
        }
        Optional<LicenseTokenRepository.TokenRow> row =
                repo.byTokenHash(FieldCrypto.codeHash(token.trim()));
        if (row.isEmpty()) {
            return new HeartbeatResult(HeartbeatStatus.EXPIRED, null);
        }
        var t = row.get();
        Instant now = Instant.now();
        if (t.expiresAt().isBefore(now)) {
            return new HeartbeatResult(HeartbeatStatus.EXPIRED, null);
        }
        String subscriptionStatus = repo.subscriptionStatus(t.subscriptionId());
        if (!"ACTIVE".equals(subscriptionStatus)) {
            return new HeartbeatResult(HeartbeatStatus.INACTIVE, null);
        }
        Instant graceUntil = now.plus(GRACE);
        repo.touchHeartbeat(t.id(), Timestamp.from(graceUntil));
        return new HeartbeatResult(HeartbeatStatus.ACTIVE, graceUntil);
    }
}
