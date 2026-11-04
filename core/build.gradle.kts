// core: libreria compartida del portfolio (logica de conversion, licencias, criptografia).
// Sin JavaFX: la logica vive aqui desacoplada de la interfaz para poder probarla de punta a punta.
plugins {
    java
    id("org.javamodularity.moduleplugin") version "2.1.0"
}

group = "com.casl"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val junitVersion = "5.12.1"
val junitPlatformVersion = "1.12.1"

dependencies {
    // JDBC driver de Supabase/Postgres (requisito fase 4.2); modular jar, entra en el jlink.
    implementation("org.postgresql:postgresql:42.7.3")

    // H2 solo en tests: esquema/portabilidad real sin credenciales de Supabase.
    testImplementation("com.h2database:h2:2.2.224")

    testImplementation("org.junit.jupiter:junit-jupiter-api:${junitVersion}")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${junitPlatformVersion}")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Depuración: los motivos de omisión se loguean en WARNING y aparecen en consola.
    testLogging.showStandardStreams = true
}

// Unit tests: rapidos y deterministas, corren en cada build.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Blackbox tests: compilan y corren en classpath para no tocar el module-info del modulo.
tasks.compileTestJava {
    modularity.inferModulePath.set(false)
}

// Integration test: convierte archivos reales con ffmpeg.
// check la ejecuta, asi que cualquier cambio que la rompa falla el build.
val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Ejecuta las pruebas de integracion (convierten archivos reales con ffmpeg)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}

// DB integration test: Supabase real SOLO si hay credenciales (env SUPABASE_DB_*),
// skip automatico si no (mapa de pruebas: tag db-integration).
val dbIntegrationTest = tasks.register<Test>("dbIntegrationTest") {
    group = "verification"
    description = "Pruebas contra Supabase/Postgres real (tag db-integration); skip sin credenciales."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("db-integration")
    }
}

tasks.check {
    dependsOn(integrationTest)
    dependsOn(dbIntegrationTest)
}
