// app-v2-freemium: MP3Converter v2.0.0 — el proyecto pay-to-pro del portfolio.
// Free: 92 kbps + watermark audible + .opus bloqueado. Pro (fase 3/4): activacion por licencia.
plugins {
    java
    application
    id("org.javamodularity.moduleplugin") version "2.1.0"
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.jlink") version "4.2.0"
}

group = "com.casl"
version = "2.0-SNAPSHOT"

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
    mainModule.set("com.casl.mp3converter.app2")
    mainClass.set("com.casl.mp3converter.v2.ApplicationV2")
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    implementation(project(":core"))
}

jlink {
    imageZip.set(layout.buildDirectory.file("/distributions/app-${javafx.platform.classifier}.zip"))
    options.set(listOf("--strip-debug", "--no-header-files", "--no-man-pages"))
    launcher {
        name = "mp3ConverterFree"
    }
    jpackage {
        imageName = "mp3ConverterFree"
        installerName = "mp3ConverterFree"
        appVersion = "2.0"
        vendor = "casl"
        icon = file("nuevo-logo.ico").absolutePath
        // The app-image output contains mp3ConverterFree.exe ready to execute;
        // installer packages (msi/exe) additionally require the WiX toolset.
        imageOptions = listOf()
        installerOptions = listOf("--win-menu", "--win-shortcut", "--win-dir-chooser")
    }
}
