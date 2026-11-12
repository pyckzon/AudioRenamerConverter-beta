package com.casl.mp3converter.v3.saas;

import com.casl.mp3converter.core.Mp3ConversionService;
import com.casl.mp3converter.core.QualityPolicy;
import com.casl.mp3converter.core.WatermarkAsset;

/**
 * Frente único de las reglas del tier en v3 (Fase 9): como en v2, la app
 * NUNCA decide por sí sola — siempre pregunta aquí. La diferencia es la
 * FUENTE DE VERDAD: ya no es la .lic local, sino la SUSCRIPCIÓN verificada
 * contra el backend SaaS (heartbeat + gracia offline de 7 días).
 * <p>
 * Fase 9.1: sólo el estado local y el branding. La conexión real al backend
 * (login → checkout web → token → heartbeat) llega en 9.2/9.3/9.4.
 */
public final class FeatureGate {

    /** Estado del modo Pro; 9.2+ lo enciende el backend (suscripción ACTIVE). */
    private static volatile boolean proEnabled = false;

    private FeatureGate() {
    }

    /**
     * Al arrancar la app (equivalente del refreshFromLicenseFile de v2):
     * 9.2+ leerá el token de licencia local, hará heartbeat contra el backend
     * y aplicará la tolerancia offline. Por ahora degrada a FREE sin lanzar.
     */
    public static void refreshAtStartup() {
        // Fase 9.2: token local -> heartbeat -> ACTIVE/INACTIVE/EXPIRED + gracia.
        setProEnabled(false);
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
