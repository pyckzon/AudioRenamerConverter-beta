package com.casl.mp3converter.saas.api;

import com.casl.mp3converter.saas.auth.JwtAuthFilter;
import com.casl.mp3converter.saas.license.LicenseTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * FASE 8.4 — Contrato de licencia (docs/saas/ARCHITECTURE.md §2):
 * POST /api/license/issue     (JWT)     -> 201 {token, graceUntil} | 403
 * POST /api/license/heartbeat (token)   -> 200 {status, graceUntil} | 401
 */
@RestController
@RequestMapping("/api/license")
public class LicenseControllerSaaS {

    private final LicenseTokenService tokens;

    public LicenseControllerSaaS(LicenseTokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/issue")
    public ResponseEntity<?> issue(HttpServletRequest request) {
        var result = tokens.issue(userId(request));
        if (result.ok()) {
            return ResponseEntity.status(201).body(Map.of(
                    "token", result.token().token(),
                    "graceUntil", result.token().expiresAt().toString()));
        }
        return ResponseEntity.status(403)
                .body(Map.of("error", "sin suscripción activa"));
    }

    public record HeartbeatRequest(String token) { }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestBody HeartbeatRequest req) {
        var result = tokens.heartbeat(req.token());
        return switch (result.status()) {
            case ACTIVE -> ResponseEntity.ok(Map.of(
                    "status", "ACTIVE",
                    "graceUntil", result.graceUntil().toString()));
            case INACTIVE -> ResponseEntity.status(401)
                    .body(Map.of("status", "INACTIVE", "error", "suscripción no activa"));
            case EXPIRED -> ResponseEntity.status(401)
                    .body(Map.of("status", "EXPIRED", "error", "token vencido o desconocido"));
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
