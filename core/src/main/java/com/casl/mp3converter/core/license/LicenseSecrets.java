package com.casl.mp3converter.core.license;

/**
 * Llaves del demoware (portfolio). REQUISITO 2.4 del plan: el archivo de licencia es un
 * binario junto al .exe protegido AES-256-GCM + HMAC. Las llaves viven embebidas como
 * en cualquier licenciamiento offline de escritorio; quien de-ensambla el binario podría
 * extraerlas, y ese límite es explícito y documentado (la protección dura llega con el
 * heartbeat online de la fase SaaS).
 */
final class LicenseSecrets {

    /** AES-256-GCM master key de las licencias (32 bytes). */
    static final byte[] LICENSE_ENCRYPTION_KEY = new byte[]{
            (byte) 0xae, (byte) 0xac, 0x5f, 0x28, (byte) 0x99, 0x1e, 0x4e, 0x33,
            0x29, (byte) 0xca, 0x09, (byte) 0xb8, 0x10, (byte) 0xf1, (byte) 0xf9, (byte) 0x97,
            (byte) 0xec, 0x0c, (byte) 0xda, (byte) 0x8e, (byte) 0xce, (byte) 0xb3, 0x73, 0x45,
            0x18, (byte) 0xf0, (byte) 0xb4, 0x73, 0x14, 0x62, (byte) 0xe1, 0x44
    };

    /** HMAC-SHA256 signing key de las licencias (32 bytes). */
    static final byte[] LICENSE_SEPARATOR_KEY = new byte[]{
            (byte) 0xb6, 0x75, 0x57, 0x5b, (byte) 0xd9, 0x78, 0x6c, (byte) 0xf5,
            (byte) 0xf8, 0x42, 0x1f, (byte) 0xc4, (byte) 0x9a, 0x29, 0x0b, 0x50,
            0x1b, (byte) 0x83, (byte) 0xb6, (byte) 0x8e, (byte) 0x9c, 0x0b, 0x67, 0x70,
            (byte) 0xc8, 0x77, (byte) 0xed, 0x5c, (byte) 0xea, 0x49, 0x2a, 0x7f
    };

    private LicenseSecrets() {
    }
}
