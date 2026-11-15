package com.casl.mp3converter.saas.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Date;
import java.util.logging.Logger;

/**
 * FASE 8.2 — Emisión y verificación de JWT de acceso (15 min).
 * <p>
 * El secreto NUNCA va hardcoded: primero system property {@code saas.jwt.secret}
 * (tests), luego env {@code SAAS_JWT_SECRET} (prod). Si no hay ninguno (dev),
 * genera uno EFÍMERO con aviso en log: los tokens mueren al reiniciar, que es
 * el comportamiento seguro para desarrollo.
 */
@Service
public class JwtService {

    private static final Logger LOGGER = Logger.getLogger(JwtService.class.getName());
    static final Duration TTL = Duration.ofMinutes(15);

    private final SecretKey key;

    public JwtService() {
        String secret = System.getProperty("saas.jwt.secret");
        if (secret == null || secret.isBlank()) {
            secret = System.getenv("SAAS_JWT_SECRET");
        }
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = Keys.hmacShaKeyFor(random);
            LOGGER.warning("Sin SAAS_JWT_SECRET: usando secreto EFIMERO de desarrollo "
                    + "(los tokens expiran al reiniciar).");
        } else {
            // normaliza a 256 bits con SHA-256: acepta secretos de cualquier longitud
            this.key = Keys.hmacShaKeyFor(sha256(secret));
        }
    }

    /** Token de acceso para el usuario dado (sub = id, exp = ahora + 15 min). */
    public String issue(long userId) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + TTL.toMillis());
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /** Valida el token y devuelve el userId; lanza JwtException si es inválido. */
    public long verify(String token) throws JwtException {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return Long.parseLong(claims.getSubject());
    }

    public long ttlSeconds() {
        return TTL.toSeconds();
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible: " + e, e);
        }
    }
}
