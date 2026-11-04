module com.casl.mp3converter.core {
    requires java.logging;
    requires java.sql;
    requires org.postgresql.jdbc;
    exports com.casl.mp3converter.core;
    exports com.casl.mp3converter.core.license;
    exports com.casl.mp3converter.core.db;
}
