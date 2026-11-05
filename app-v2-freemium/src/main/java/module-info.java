module com.casl.mp3converter.app2 {
    requires com.casl.mp3converter.core;
    requires java.logging;
    requires javafx.controls;
    requires javafx.fxml;

    opens com.casl.mp3converter.v2 to javafx.fxml;
    exports com.casl.mp3converter.v2;
}
