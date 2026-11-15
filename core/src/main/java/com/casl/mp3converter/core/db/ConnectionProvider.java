package com.casl.mp3converter.core.db;

import java.sql.Connection;
import java.sql.SQLException;

/** Acceso "one-operation-one-connection" a la base de datos (desktop, bajo volumen). */
@FunctionalInterface
public interface ConnectionProvider {
    Connection get() throws SQLException;
}
