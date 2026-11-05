package com.casl.mp3converter.core.db;

import com.casl.mp3converter.core.license.ActivationCodeGenerator;
import com.casl.mp3converter.core.license.FieldCrypto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Códigos de activación (requisito 2.6): el código SÍ se muestra en claro al usuario
 * en el diálogo de la fase 5, pero en la base de datos vive HASHEADO (lookup con
 * {@link FieldCrypto#codeHash}) y CIFRADO ({@code code_enc}, con la clave derivada
 * del propio usuario) para recuperación. Cada nueva emisión crea transacción única;
 * los códigos previos ACTIVE de la misma cuenta pasan a DEACTIVATED ("clave única").
 */
public class ActivationRepository {

    final ConnectionProvider connectionProvider;

    public ActivationRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    public record ActivationCode(long activationId, String code, long userId) {
    }

    /** Vista mínima para el LicenseController (fase 5) al escribir el .lic local. */
    public record LicensePayloadView(long activationId, long userId, byte[] codeEnc, byte[] userSalt) {
    }

    /**
     * Emisión transaccional: upsert de usuario (dedupe) + código nuevo ACTIVE.
     * El código returna EN CLARO una única vez, para el diálogo del requisito 2.6.
     */
    public ActivationCode emitCode(CustomerProfile profile, String tier) throws SQLException {
        try (Connection connection = connectionProvider.get()) {
            SchemaInitializer.ensureSchema(connection);
            connection.setAutoCommit(false);
            try {
                UserRepository users = new UserRepository(connectionProvider);
                UserRepository.UserId userId = users.upsertUser(connection, profile, tier);

                String code = ActivationCodeGenerator.generate();
                String codeHash = FieldCrypto.codeHash(code);
                byte[] codeEncrypted = FieldCrypto.encryptField(
                        profile.correo(), profile.apellido(), userId.salt(), code);

                // "clave única": un solo ACTIVE por usuario
                try (PreparedStatement deactivate = connection.prepareStatement(
                        "UPDATE activations SET status='DEACTIVATED', deactivated_at=? "
                                + "WHERE user_id=? AND status='ACTIVE'")) {
                    deactivate.setTimestamp(1, Timestamp.from(Instant.now()));
                    deactivate.setLong(2, userId.id());
                    deactivate.executeUpdate();
                }

                long activationId;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO activations (user_id, code_hash, code_enc, salt, issued_at, status) "
                                + "VALUES (?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setLong(1, userId.id());
                    insert.setString(2, codeHash);
                    insert.setBytes(3, codeEncrypted);
                    insert.setBytes(4, userId.salt());
                    insert.setTimestamp(5, Timestamp.from(Instant.now()));
                    insert.setString(6, "ACTIVE");
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("sin id generado para la activación");
                        }
                        activationId = keys.getLong(1);
                    }
                }
                connection.commit();
                return new ActivationCode(activationId, code, userId.id());
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Valida un código: formato, hash existente y status ACTIVE.
     * Código falso o desactivado => Optional.empty (nada de detalles al llamador).
     */
    public Optional<LicensePayloadView> lookupActiveCode(String code) throws SQLException {
        if (!ActivationCodeGenerator.isFormatValid(code)) {
            return Optional.empty();
        }
        String codeHash = FieldCrypto.codeHash(code);
        try (Connection connection = connectionProvider.get()) {
            SchemaInitializer.ensureSchema(connection);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id, user_id, code_enc, salt FROM activations "
                            + "WHERE code_hash=? AND status='ACTIVE'")) {
                query.setString(1, codeHash);
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new LicensePayloadView(
                            rs.getLong("id"), rs.getLong("user_id"),
                            rs.getBytes("code_enc"), rs.getBytes("salt")));
                }
            }
        }
    }

    /** Recuperación para el mismo usuario: descifra code_enc con su clave (correo+apellido). */
    public Optional<String> recoverCode(CustomerProfile profile) throws SQLException {
        UserRepository users = new UserRepository(connectionProvider);
        String emailHash = FieldCrypto.emailHash(profile.correo());
        try (Connection connection = connectionProvider.get()) {
            SchemaInitializer.ensureSchema(connection);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT a.code_enc, u.salt FROM activations a JOIN users u ON u.id=a.user_id "
                            + "WHERE u.email_hash=? AND a.status='ACTIVE'")) {
                query.setString(1, emailHash);
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    byte[] codeEnc = rs.getBytes("code_enc");
                    byte[] salt = rs.getBytes("salt");
                    try {
                        // pepper incorrecto (otro apellido) => no descifra => no es del usuario:
                        return Optional.of(FieldCrypto.decryptField(
                                profile.correo(), profile.apellido(), salt, codeEnc));
                    } catch (IllegalStateException e) {
                        return Optional.empty();
                    }
                }
            }
        }
    }
}
