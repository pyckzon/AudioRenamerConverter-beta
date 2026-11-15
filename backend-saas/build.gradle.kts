// backend-saas: STUB de la Fase 7 (borrador arquitectónico, sin implementar).
// Solo declara la base Spring Boot 3.x y la clase de arranque; los endpoints
// reales (auth JWT, subscriptions, checkout, license issue/heartbeat) se
// implementaran cuando se decida, segun docs/saas/ARCHITECTURE.md.
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
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
