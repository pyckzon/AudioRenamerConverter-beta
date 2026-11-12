module com.casl.mp3converter.app3 {
    requires com.casl.mp3converter.core;
    requires java.logging;
    requires javafx.controls;
    requires javafx.fxml;

    opens com.casl.mp3converter.v3 to javafx.fxml;
    exports com.casl.mp3converter.v3;
}
