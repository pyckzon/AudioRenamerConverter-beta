package com.casl.mp3converter.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FASE 7 — STUB del backend SaaS (Spring Boot 3.x, vacio a proposito).
 * <p>
 * Solo comprueba que la base del proyecto compila y sabe arrancar. Los
 * endpoints del contrato (auth/register + login JWT, subscriptions,
 * payments/checkout, license/issue + heartbeat), las tablas nuevas y los
 * perfiles dev/prod viven DOCUMENTADOS en {@code docs/saas/ARCHITECTURE.md}
 * y se implementan cuando se decida la infraestructura.
 */
@SpringBootApplication
public class SaasApplication {

    public static void main(String[] args) {
        SpringApplication.run(SaasApplication.class, args);
    }
}
