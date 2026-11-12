package com.casl.mp3converter.saas.config;

import com.casl.mp3converter.saas.auth.JwtAuthFilter;
import com.casl.mp3converter.saas.auth.JwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FASE 8.3 — Registro del filtro JWT SÓLO en los endpoints de negocio
 * (auth y health quedan públicos, igual que el webhook que valida el gateway).
 */
@Configuration
public class WebConfig {

    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(JwtService jwt) {
        var registration = new FilterRegistrationBean<>(new JwtAuthFilter(jwt));
        registration.addUrlPatterns(
                "/api/subscriptions", "/api/subscriptions/*",
                "/api/payments/checkout",
                "/api/license/issue", "/api/license/heartbeat");
        registration.setOrder(1);
        return registration;
    }
}
