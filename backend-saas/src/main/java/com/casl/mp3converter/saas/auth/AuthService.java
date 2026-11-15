package com.casl.mp3converter.saas.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * FASE 8.2 — Lógica de registro/login del SaaS.
 * Reglas: correo normalizado (trim+lowercase, igual que v2), password ≥ 8
 * con BCrypt (nunca en claro, nunca en logs), dedupe por email_hash.
 */
@Service
public class AuthService {

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SaasUserRepository users;
    private final JwtService jwt;

    public AuthService(SaasUserRepository users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    public record RegisterRequest(String email, String password, String firstName, String lastName) { }

    public enum RegisterError { INVALID_EMAIL, WEAK_PASSWORD, INVALID_NAME, DUPLICATE_EMAIL }

    public record RegisterResult(Long userId, RegisterError error) {
        public boolean ok() { return error == null; }
    }

    public RegisterResult register(RegisterRequest req) {
        String correo = normalize(req.email());
        String apellido = req.lastName() == null ? "" : req.lastName().trim();
        String nombre = req.firstName() == null ? "" : req.firstName().trim();

        if (correo == null || !correo.matches("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$")) {
            return new RegisterResult(null, RegisterError.INVALID_EMAIL);
        }
        if (req.password() == null || req.password().length() < 8) {
            return new RegisterResult(null, RegisterError.WEAK_PASSWORD);
        }
        if (nombre.length() < 2 || apellido.length() < 2
                || !nombre.matches("[\\p{L} ]+") || !apellido.matches("[\\p{L} ]+")) {
            return new RegisterResult(null, RegisterError.INVALID_NAME);
        }

        return users.register(correo, apellido, bcrypt.encode(req.password()), nombre)
                .map(id -> new RegisterResult(id, null))
                .orElse(new RegisterResult(null, RegisterError.DUPLICATE_EMAIL));
    }

    public record LoginRequest(String email, String password) { }

    public record LoginResult(String accessToken, long userId, long expiresIn) {
        public boolean ok() { return accessToken != null; }
    }

    /** Login: BCrypt contra el hash almacenado; JWT de 15 min si es correcto. */
    public LoginResult login(LoginRequest req) {
        String correo = normalize(req.email());
        if (correo == null || req.password() == null) {
            return new LoginResult(null, 0, 0);
        }
        return users.credentialsByEmail(correo)
                .filter(c -> bcrypt.matches(req.password(), c.passwordHash()))
                .map(c -> new LoginResult(jwt.issue(c.id()), c.id(), jwt.ttlSeconds()))
                .orElse(new LoginResult(null, 0, 0));
    }

    /** Normalización idéntica a v2: el hash/cifrado dependen del correo normalizado. */
    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
