package com.casl.mp3converter.saas.billing;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * FASE 8.3 — Seed idempotente de los planes por defecto (MXN, el país por
 * defecto del dropdown de v2): mensual y anual. Los precios son de demostración.
 * {@code @Order(100)}: DESPUÉS del esquema (SaasSchemaConfig es @Order(0)).
 */
@Component
@Order(100)
public class PlanSeeder implements ApplicationRunner {

    private final BillingRepository repo;

    public PlanSeeder(BillingRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        repo.seedPlanIfAbsent("pro-mensual", 9_900, "MXN", "monthly");
        repo.seedPlanIfAbsent("pro-anual", 99_900, "MXN", "annual");
    }
}
