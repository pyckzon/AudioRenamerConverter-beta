module com.casl.mp3converter.app3 {
    requires com.casl.mp3converter.core;
    requires java.logging;
    requires java.desktop;
    requires java.net.http;
    requires javafx.controls;
    requires javafx.fxml;
    // SOLO compilación: lo usan los tests (stub HTTP del JDK), nunca el runtime.
    requires static jdk.httpserver;

    opens com.casl.mp3converter.v3 to javafx.fxml;
    exports com.casl.mp3converter.v3;
}
