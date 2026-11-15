package com.casl.mp3converter.saas.api;

import com.casl.mp3converter.saas.auth.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * FASE 8.2 — Contrato de auth (docs/saas/ARCHITECTURE.md §2):
 * POST /api/auth/register  -> 201 {userId} | 409 duplicado | 400 validación
 * POST /api/auth/login     -> 200 {accessToken,userId,expiresIn} | 401
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthService.RegisterRequest req) {
        var result = auth.register(req);
        return switch (result.error()) {
            case null -> ResponseEntity.status(201).body(Map.of("userId", result.userId()));
            case DUPLICATE_EMAIL -> ResponseEntity.status(409)
                    .body(Map.of("error", "email ya registrado"));
            case INVALID_EMAIL -> bad("email inválido");
            case WEAK_PASSWORD -> bad("la contraseña debe tener al menos 8 caracteres");
            case INVALID_NAME -> bad("nombre y apellido: solo letras, mínimo 2");
        };
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthService.LoginRequest req) {
        var result = auth.login(req);
        return result.ok()
                ? ResponseEntity.ok(Map.of(
                        "accessToken", result.accessToken(),
                        "userId", result.userId(),
                        "expiresIn", result.expiresIn()))
                : ResponseEntity.status(401).body(Map.of("error", "credenciales inválidas"));
    }

    private static ResponseEntity<Map<String, String>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
