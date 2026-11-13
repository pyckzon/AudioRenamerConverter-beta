// app-v3-saas: MP3Converter v3.0.0 — fork de app-v2-freemium con el FeatureGate
// por SUSCRIPCIÓN al backend SaaS (Fase 9). El free mantiene las reglas de v2
// (92 kbps + watermark + .opus bloqueado); el Pro se obtiene con suscripción
// activa verificada por heartbeat (gracia offline de 7 días), NO con .lic local.
plugins {
    java
    application
    id("org.javamodularity.moduleplugin") version "2.1.0"
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.jlink") version "4.2.0"
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

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

application {
    mainModule.set("com.casl.mp3converter.app3")
    mainClass.set("com.casl.mp3converter.v3.LauncherV3")
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation(project(":core"))

    // Fase 9.4: el E2E de la app arranca el backend REAL como proceso JVM hijo
    // (java -cp con el runtime de :backend-saas) — HTTP de verdad sin acoplar
    // Spring al module-info del módulo FX.
    val backendCp by configurations.creating {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies.add("backendCp", project(":backend-saas"))
    // la resolución de :backend-saas pierde SU BOM: lo importamos aquí también:
    dependencies.add("backendCp", platform("org.springframework.boot:spring-boot-dependencies:3.5.0"))

    val junitVersion = "5.12.1"
    val junitPlatformVersion = "1.12.1"
    testImplementation("org.junit.jupiter:junit-jupiter-api:${junitVersion}")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${junitPlatformVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
}

// Unit tests: rápidos y deterministas.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Blackbox tests on classpath (same convention as core/app2, no module-path touch).
tasks.compileTestJava {
    modularity.inferModulePath.set(false)
}

// Fase 9.4: el E2E lanza el backend REAL (proceso hijo) con el classpath de
// runtime de :backend-saas, resuelto AQUÍ y pasado como system property.
tasks.test {
    val backendCp = configurations.getByName("backendCp")
    doFirst {
        systemProperty("saas.e2e.backend.classpath",
                backendCp.resolve().joinToString(File.pathSeparator) { it.absolutePath })
    }
}

jlink {
    imageZip.set(layout.buildDirectory.file("/distributions/app-${javafx.platform.classifier}.zip"))
    options.set(listOf("--strip-debug", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "mp3ConverterSaaS"
    }
    jpackage {
        imageName = "mp3ConverterSaaS"
        installerName = "mp3ConverterSaaS"
        appVersion = "3.0"
        vendor = "casl"
        icon = file("nuevo-logo.ico").absolutePath
        imageOptions = listOf()
        installerOptions = listOf("--win-menu", "--win-shortcut", "--win-dir-chooser")
    }
}
