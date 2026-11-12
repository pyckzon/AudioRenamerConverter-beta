package com.casl.mp3converter.saas.config;

import com.casl.mp3converter.core.db.SchemaInitializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * FASE 8 — Aplica la migración idempotente {@code /db/migration/saas/V1__saas.sql}
 * al arrancar (H2 dev o Postgres real por env). Reutiliza el inicializador de
 * core: una sola lógica de esquema para v2 y el SaaS.
 * {@code @Order(0)}: el esquema SIEMPRE corre antes que los demás runners
 * (el PlanSeeder escribe en saas.plans y la carrera sin orden es no
 * determinística — se probó: a veces ganaba el seeder y el contexto moría).
 */
@Configuration
public class SaasSchemaConfig {

    private static final Logger LOGGER = Logger.getLogger(SaasSchemaConfig.class.getName());

    @Bean
    @Order(0)
    ApplicationRunner saasSchemaInitializer(DataSource dataSource) {
        return (ApplicationArguments args) -> {
            try (Connection connection = dataSource.getConnection()) {
                SchemaInitializer.ensureSchema(connection, "/db/migration/saas/V1__saas.sql");
                LOGGER.info("Esquema saas.* verificado.");
            } catch (SQLException e) {
                throw new IllegalStateException("No se pudo aplicar la migración saas: " + e, e);
            }
        };
    }
}
