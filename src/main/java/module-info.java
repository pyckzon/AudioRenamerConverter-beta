module com.casl.mp3converter {
    requires java.logging;
    requires javafx.controls;
    requires javafx.fxml;

    opens com.casl.mp3converter to javafx.fxml;
    exports com.casl.mp3converter;
}