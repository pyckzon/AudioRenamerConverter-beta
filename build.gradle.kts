plugins {
    java
    application
    id("org.javamodularity.moduleplugin") version "2.1.0"
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.jlink") version "4.2.0"
}

group = "com.casl"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val junitVersion = "5.12.1"
val junitPlatformVersion = "1.12.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

application {
    mainModule.set("com.casl.mp3converter")
    mainClass.set("com.casl.mp3converter.HelloApplication")
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:${junitVersion}")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${junitPlatformVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Unit tests: they must be fast, deterministic and run on every build.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Blackbox tests: the tests are compiled and run on the classpath,
// which keeps the main module-info of the application untouched.
tasks.compileTestJava {
    modularity.inferModulePath.set(false)
}

// Integration test task ("special file"): it really converts files with ffmpeg.
// The check task depends on it, so any change that breaks it fails the build.
val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Ejecuta las pruebas de integración (convierten archivos reales con ffmpeg)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
}

tasks.check {
    dependsOn(integrationTest)
}

jlink {
    imageZip.set(layout.buildDirectory.file("/distributions/app-${javafx.platform.classifier}.zip"))
    options.set(listOf("--strip-debug", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "mp3Converter"
    }
    jpackage {
        imageName = "mp3Converter"
        installerName = "mp3Converter"
        appVersion = "1.0"
        vendor = "casl"
        icon = file("nuevo-logo.ico").absolutePath
        // The app-image output contains mp3Converter.exe ready to execute;
        // installer packages (msi/exe) additionally require the WiX toolset.
        imageOptions = listOf()
        installerOptions = listOf("--win-menu", "--win-shortcut", "--win-dir-chooser")
    }
}
