package com.casl.mp3converter.saas.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health-check mínimo (contrato fase 8.1). Los endpoints del negocio
 * (auth, subscriptions, payments, license) llegan en las sub-fases 8.2-8.4.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
