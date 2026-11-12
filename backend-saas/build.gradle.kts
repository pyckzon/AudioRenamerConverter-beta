// backend-saas: FASE 8 en construcción (antes era el stub vacío de la Fase 7).
// Base Spring Boot 3.x con BD portable (H2 dev en memoria / Postgres Supabase
// por env) y reutilización de core (SchemaInitializer, FieldCrypto). Los
// endpoints del contrato van llegando por sub-fase: ver docs/saas/ARCHITECTURE.md.
plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.casl"
version = "3.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Lógica compartida del proyecto (esquema idempotente, cripto de campos):
    implementation(project(":core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Drivers: H2 es el default de desarrollo; Postgres entra por env en prod.
    // (spring-boot-dependencies ya administra ambas versiones)
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
