package com.casl.mp3converter.saas.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * FASE 8.3 — Protección de endpoints de negocio con el JWT de la fase 8.2.
 * Sin el stack completo de Spring Security (backend ligero, contrato simple):
 * header {@code Authorization: Bearer <token>} → atributo {@code saas.userId}.
 * Sin token o token inválido → 401 JSON (nunca 500).
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    /** atributo de request con el userId autenticado. */
    public static final String USER_ID_ATTR = "saas.userId";

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                long userId = jwt.verify(header.substring(7).trim());
                request.setAttribute(USER_ID_ATTR, userId);
                chain.doFilter(request, response);
                return;
            } catch (JwtException | IllegalArgumentException ignored) {
                // token inválido/expirado: cae al 401
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"token requerido o inválido\"}");
    }
}
