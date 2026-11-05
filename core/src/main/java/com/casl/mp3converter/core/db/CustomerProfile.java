package com.casl.mp3converter.core.db;

import java.time.Instant;

/**
 * Datos del comprador, tal como el usuario los escribió en el formulario
 * (fase 5). El RECORD viaja en claro SOLO en memoria: todo lo que sale de la
 * JVM hacia la base de datos está cifrado (requisito 2.5.2).
 *
 * @param nombre   nombre (campo en claro del formulario, nunca persistido plano)
 * @param apellido apellido — PEPPER de la derivación de claves del usuario
 * @param correo   correo — componente de la SAL del usuario (besides salt aleatoria)
 * @param telefono teléfono (10 dígitos, validado en fase 5)
 */
public record CustomerProfile(String nombre, String apellido, String correo, String telefono) {
}
