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
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${junitVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
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
