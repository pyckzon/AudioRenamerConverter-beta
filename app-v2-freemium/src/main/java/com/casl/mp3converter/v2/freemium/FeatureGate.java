package com.casl.mp3converter.v2.freemium;

import com.casl.mp3converter.core.Mp3ConversionService;
import com.casl.mp3converter.core.QualityPolicy;
import com.casl.mp3converter.core.WatermarkAsset;
import com.casl.mp3converter.core.license.LicenseVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * Frente único de las reglas del tier (requisitos 2.1, 2.2, 2.3, 2.4).
 * La aplicación NUNCA decide por sí sola qué puede hacer el usuario:
 * siempre pregunta aquí. Fase 3/4: la licencia activará/actualizara {@code proEnabled}.
 */
public final class FeatureGate {

    /** Estado del modo Pro; la Fase 3/5 lo enciende desde el archivo de licencia/activación. */
    private static volatile boolean proEnabled = false;

    private FeatureGate() {
    }

    /**
     * Fase 3 (requisito 2.4): al abrir el ejecutable se lee el binario de licencia
     * ({@code mp3converter.lic}) junto al .exe y se decide el tier inicial.
     * La Fase 5 reutilizará esta misma puerta para la activación "en caliente".
     */
    public static void refreshFromLicenseFile() {
        LicenseVerifier.LicenseState state = LicenseVerifier.verify();
        setProEnabled(state.tier() == LicenseVerifier.Tier.PRO);
    }

    public static boolean isPro() {
        return proEnabled;
    }

    /** Hot enable/disable (fase 5: activación con código sin reiniciar). */
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
                ? "MP3Converter version 2.0.0 · PRO activado"
                : "MP3Converter version 2.0.0 · Versión gratuita · Calidad máxima 92 kbps";
    }

    /** Mensaje del CTA "Comprar versión Pro" (fase 5 implementará el flujo de pago real). */
    public static String buyTitle() {
        return "Comprar la versión Pro";
    }

    public static String buyNotice() {
        return "La versión Pro desbloquea:\n"
                + "  ·  Calidad MP3 de hasta 320 kbps (el free graba con 92 kbps CBR)\n"
                + "  ·  Sin el sello audible \"mp3Converter\" al inicio y al final\n"
                + "  ·  Conversión de archivos .opus\n\n"
                + "El pago se procesa con la pasarela de demo (mock): nunca se guardan\n"
                + "datos de tarjeta. Al aprobar recibes una clave de activación.\n";
    }

    /**
     * Construye el servicio de conversión según el tier vigente:
     * <ul>
     *   <li><b>Free</b> (requisitos 2.1/2.2/2.3): 92 kbps CBR sello = watermark audible + .opus bloqueado.</li>
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
