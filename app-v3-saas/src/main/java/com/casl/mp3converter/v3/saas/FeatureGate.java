package com.casl.mp3converter.v3.saas;

import com.casl.mp3converter.core.Mp3ConversionService;
import com.casl.mp3converter.core.QualityPolicy;
import com.casl.mp3converter.core.WatermarkAsset;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Frente único de las reglas del tier en v3 (Fase 9). Igual que en v2, la app
 * NUNCA decide por sí sola — siempre pregunta aquí. La FUENTE DE VERDAD es la
 * SUSCRIPCIÓN verificada contra el backend SaaS:
 * <ol>
 *   <li>token de licencia local (saas/mp3converter.saas junto al .exe);</li>
 *   <li>heartbeat contra el backend → ACTIVE renueva la gracia;</li>
 *   <li>sin red → tolerancia offline: la gracia guardada (7 días) sostiene PRO;</li>
 *   <li>INACTIVE (cancelada) / EXPIRED (>7 días sin heartbeat) → FREE y sin sesión.</li>
 * </ol>
 * Igual que v2: NUNCA lanza — cualquier fallo degrada a FREE con motivo auditable.
 */
public final class FeatureGate {

    /** Ventana de gracia offline del requisito del plan (misma que el backend). */
    public static final Duration GRACE = Duration.ofDays(7);

    /** Estado del modo Pro; lo enciende el backend (suscripción ACTIVE). */
    private static volatile boolean proEnabled = false;

    /** Último motivo auditable del estado (para la UI/logs, nunca crash). */
    private static volatile String lastReason = "sin sesión: modo gratuito";

    /** Cliente inyectable (tests); por defecto apunta al backend por env/prop. */
    private static SaasApiClient api = new SaasApiClient();

    private FeatureGate() {
    }

    /** inyección para pruebas unitarias (package-private). */
    static void setApiClient(SaasApiClient client) {
        api = client;
    }

    /**
     * Al abrir la app (equivalente del refreshFromLicenseFile de v2):
     * sin sesión → FREE; con token → heartbeat; sin red → gracia offline.
     */
    public static void refreshAtStartup() {
        var session = SaasSessionStore.load();
        if (session.isEmpty()) {
            setProEnabled(false);
            lastReason = "sin sesión: modo gratuito";
            return;
        }
        heartbeat(session.get());
    }

    /** Heartbeat contra el backend; INACTIVE/EXPIRED limpia la sesión. */
    static void heartbeat(SaasSessionStore.Session session) {
        try {
            SaasApiClient.Heartbeat hb = api.heartbeat(session.token());
            switch (hb.status()) {
                case ACTIVE -> {
                    setProEnabled(true);
                    lastReason = "suscripción activa (gracia hasta " + hb.graceUntil() + ")";
                    SaasSessionStore.save(new SaasSessionStore.Session(
                            session.token(), hb.graceUntil(), session.email()));
                }
                case INACTIVE -> {
                    setProEnabled(false);
                    lastReason = "suscripción inactiva o cancelada";
                    SaasSessionStore.clear();
                }
                case EXPIRED -> {
                    setProEnabled(false);
                    lastReason = "token vencido (más de 7 días sin verificar)";
                    SaasSessionStore.clear();
                }
            }
        } catch (IOException | InterruptedException offline) {
            if (offline instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // SIN RED: la gracia guardada sostiene PRO hasta su vencimiento:
            if (session.graceUntil().isAfter(Instant.now())) {
                setProEnabled(true);
                lastReason = "sin conexión: gracia offline hasta " + session.graceUntil();
            } else {
                setProEnabled(false);
                lastReason = "sin conexión y gracia vencida";
            }
        }
    }

    /** 9.3: tras el pago + issue, activa PRO EN CALIENTE y persiste la sesión. */
    public static void activateWithToken(String token, Instant graceUntil, String email) {
        setProEnabled(true);
        lastReason = "suscripción activa (gracia hasta " + graceUntil + ")";
        SaasSessionStore.save(new SaasSessionStore.Session(token, graceUntil, email));
    }

    /** Limpia la sesión local (logout/baja manual). */
    public static void clearSession() {
        SaasSessionStore.clear();
        setProEnabled(false);
        lastReason = "sesión cerrada: modo gratuito";
    }

    public static String lastReason() {
        return lastReason;
    }

    /** package-privado: reset completo para tests. */
    static void resetForTests() {
        SaasSessionStore.clear();
        setProEnabled(false);
        lastReason = "reset de pruebas";
    }

    public static boolean isPro() {
        return proEnabled;
    }

    /** Hot flip (mismo patrón que v2: la UI se refresca sin reiniciar). */
    public static void setProEnabled(boolean enabled) {
        proEnabled = enabled;
    }

    /** Título grande de la portada (encabezado) según el tier actual. */
    public static String headerTitle() {
        return isPro()
                ? "Convertidor a MP3 (PRO)"
                : "Convertidor a MP3 (versión gratuita)";
    }

    /** Footer de la ventana según el tier actual. */
    public static String footer() {
        return isPro()
                ? "MP3Converter version 3.0.0 · PRO por suscripción activa"
                : "MP3Converter version 3.0.0 · Versión gratuita · Calidad máxima 92 kbps";
    }

    /** Mensaje del CTA de suscripción (9.3 lo convierte en flujo real). */
    public static String buyTitle() {
        return "Suscribirse a la versión Pro";
    }

    public static String buyNotice() {
        return "La suscripción Pro desbloquea:\n"
                + "  ·  Calidad MP3 de hasta 320 kbps (el free graba con 92 kbps CBR)\n"
                + "  ·  Sin el sello audible \"mp3Converter\" al inicio y al final\n"
                + "  ·  Conversión de archivos .opus\n\n"
                + "El pago ocurre en la web de la pasarela (checkout): la app jamás\n"
                + "pide ni guarda datos de tarjeta. Tu suscripción se verifica en\n"
                + "línea y tolera hasta 7 días sin conexión.\n";
    }

    /**
     * Construye el servicio de conversión según el tier vigente (mismas reglas
     * del negocio que v2, reuso directo del core):
     * <ul>
     *   <li><b>Free</b>: 92 kbps CBR + watermark audible + .opus bloqueado.</li>
     *   <li><b>Pro</b>: calidad VBR completa, sin watermark y sin bloqueos.</li>
     * </ul>
     */
    public static Mp3ConversionService createService() {
        if (isPro()) {
            return new Mp3ConversionService(QualityPolicy.full());
        }
        return Mp3ConversionService.freeTier(WatermarkAsset.extractToTempFile());
    }
}
