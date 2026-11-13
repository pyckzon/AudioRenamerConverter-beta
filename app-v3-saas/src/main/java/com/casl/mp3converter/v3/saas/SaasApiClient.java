package com.casl.mp3converter.v3.saas;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FASE 9.2 — Cliente HTTP del backend SaaS (java.net.http, cero dependencias).
 * <p>
 * Base URL: system property {@code mp3converter.saas.api} → env
 * {@code SAAS_API_URL} → {@code http://localhost:8080} (dev).
 * <p>
 * REGLA del proyecto: los errores son DATOS para la UI — un fallo de red lanza
 * {@link IOException} y FeatureGate lo convierte en tolerancia offline; los
 * 401/403 del negocio se devuelven como estados (nunca excepciones silenciosas).
 */
public class SaasApiClient {

    /** Resultado del heartbeat: ACTIVE (gracia renovada) | INACTIVE | EXPIRED. */
    public record Heartbeat(Status status, Instant graceUntil) {
        public enum Status { ACTIVE, INACTIVE, EXPIRED }
    }

    public record Checkout(String checkoutUrl, String externalId) { }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;

    public SaasApiClient() {
        String url = System.getProperty("mp3converter.saas.api");
        if (url == null || url.isBlank()) {
            url = System.getenv("SAAS_API_URL");
        }
        this.baseUrl = url == null || url.isBlank() ? "http://localhost:8080" : url.trim();
    }

    /** Para tests: base URL explícita. */
    SaasApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** POST /api/auth/register → 201; 409 → IOException "ya registrado". */
    public void register(String email, String password, String firstName, String lastName)
            throws IOException, InterruptedException {
        String body = "{\"email\":\"" + esc(email) + "\",\"password\":\"" + esc(password)
                + "\",\"firstName\":\"" + esc(firstName)
                + "\",\"lastName\":\"" + esc(lastName) + "\"}";
        HttpResponse<String> res = post("/api/auth/register", body, null);
        if (res.statusCode() == 201) {
            return;
        }
        String motivo = res.statusCode() == 409 ? "el correo ya está registrado"
                : extractString(res.body(), "error");
        throw new IOException("registro falló (HTTP " + res.statusCode() + "): "
                + (motivo == null ? "error de validación" : motivo));
    }

    /** POST /api/auth/login → accessToken (JWT). 401 → IOException con motivo. */
    public String login(String email, String password) throws IOException, InterruptedException {
        String body = "{\"email\":\"" + esc(email) + "\",\"password\":\"" + esc(password) + "\"}";
        HttpResponse<String> res = post("/api/auth/login", body, null);
        if (res.statusCode() == 200) {
            return requireString(res.body(), "accessToken");
        }
        throw new IOException("login falló (HTTP " + res.statusCode() + ")");
    }

    /** POST /api/payments/checkout (JWT) → sesión de pago web (gateway). */
    public Checkout checkout(String jwt, String planCode) throws IOException, InterruptedException {
        String body = "{\"planCode\":\"" + esc(planCode) + "\"}";
        HttpResponse<String> res = post("/api/payments/checkout", body, jwt);
        if (res.statusCode() == 201) {
            return new Checkout(requireString(res.body(), "checkoutUrl"),
                    requireString(res.body(), "externalId"));
        }
        throw new IOException("checkout falló (HTTP " + res.statusCode() + ")");
    }

    /** POST /api/license/issue (JWT) → token de licencia + gracia. */
    public record Issued(String token, Instant graceUntil) { }

    public Issued issue(String jwt) throws IOException, InterruptedException {
        HttpResponse<String> res = post("/api/license/issue", "{}", jwt);
        if (res.statusCode() == 201) {
            return new Issued(requireString(res.body(), "token"),
                    Instant.parse(requireString(res.body(), "graceUntil")));
        }
        throw new IOException("issue falló (HTTP " + res.statusCode()
                + "): " + (res.statusCode() == 403 ? "sin suscripción activa" : "error del backend"));
    }

    /** POST /api/license/heartbeat (autenticado con el TOKEN de licencia). */
    public Heartbeat heartbeat(String token) throws IOException, InterruptedException {
        String body = "{\"token\":\"" + esc(token) + "\"}";
        HttpResponse<String> res = post("/api/license/heartbeat", body, null);
        if (res.statusCode() == 200) {
            return new Heartbeat(Heartbeat.Status.ACTIVE,
                    Instant.parse(requireString(res.body(), "graceUntil")));
        }
        if (res.statusCode() == 401) {
            String status = extractString(res.body(), "status");
            return new Heartbeat("INACTIVE".equals(status)
                    ? Heartbeat.Status.INACTIVE : Heartbeat.Status.EXPIRED, null);
        }
        throw new IOException("heartbeat falló (HTTP " + res.statusCode() + ")");
    }

    // --- HTTP helpers --------------------------------------------------------

    private HttpResponse<String> post(String path, String jsonBody, String bearerJwt)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearerJwt != null) {
            builder.header("Authorization", "Bearer " + bearerJwt);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requireString(String json, String key) throws IOException {
        String value = extractString(json, key);
        if (value == null) {
            throw new IOException("respuesta sin \"" + key + "\": " + json);
        }
        return value;
    }

    /** Extractor JSON mínimo (respuestas planas del backend; sin dependencias). */
    static String extractString(String json, String key) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
