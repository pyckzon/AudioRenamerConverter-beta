package com.casl.mp3converter.core.db;

import com.casl.mp3converter.core.license.FieldCrypto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de usuarios (requisito 2.5.2). Reglas del plan:
 * <ul>
 *   <li>Se escribe SOLO hash/cifrado: email_hash, email_enc, nombre/apellido/teléfono ENC.</li>
 *   <li>Anti-duplicado por {@code email_hash}: re-activar la misma cuenta no duplica.</li>
 *   <li>El salt aleatorio se persiste por usuario (necesario para re-derivar la clave).</li>
 * </ul>
 * SQL portable: H2 (en memoria, para tests) y PostgreSQL (Supabase en producción).
 */
public class UserRepository {

    final ConnectionProvider connectionProvider;

    public UserRepository(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    /** Resultado del upsert con des-duplicado. */
    public record UserId(long id, boolean created, byte[] salt) {
    }

    /** Crea el usuario cifrado o actualiza el existente por email_hash (sin duplicados). */
    public UserId upsertUser(CustomerProfile profile, String tier) throws SQLException {
        try (Connection connection = connectionProvider.get()) {
            SchemaInitializer.ensureSchema(connection);
            return upsertUser(connection, profile, tier);
        }
    }

    /** Igual que {@link #upsertUser} pero SOBRE UNA conexión dada (transacciones). */
    UserId upsertUser(Connection connection, CustomerProfile profile, String tier) throws SQLException {
        String emailHash = FieldCrypto.emailHash(profile.correo());

        Optional<Long> existing = findIdByEmailHash(connection, emailHash);
        if (existing.isPresent()) {
            long id = existing.get();
            byte[] salt = readSalt(connection, id);
            // actualiza el contacto con la SAL CONSERVADA del usuario (segmento 2.5.2):
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE users SET first_name_enc=?, last_name_enc=?, phone_enc=?, "
                            + "email_enc=?, tier=?, updated_at=? WHERE id=?")) {
                update.setBytes(1, FieldCrypto.encryptField(
                        profile.correo(), profile.apellido(), salt, profile.nombre()));
                update.setBytes(2, FieldCrypto.encryptField(
                        profile.correo(), profile.apellido(), salt, profile.apellido()));
                update.setBytes(3, FieldCrypto.encryptField(
                        profile.correo(), profile.apellido(), salt, profile.telefono()));
                update.setBytes(4, FieldCrypto.encryptField(
                        profile.correo(), profile.apellido(), salt, profile.correo()));
                update.setString(5, tier == null ? "FREE" : tier);
                update.setTimestamp(6, Timestamp.from(Instant.now()));
                update.setLong(7, id);
                update.executeUpdate();
            }
            return new UserId(id, false, salt);
        }

        byte[] salt = FieldCrypto.newSalt();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO users (email_hash, email_enc, first_name_enc, last_name_enc, "
                        + "phone_enc, tier, salt) VALUES (?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, emailHash);
            insert.setBytes(2, FieldCrypto.encryptField(
                    profile.correo(), profile.apellido(), salt, profile.correo()));
            insert.setBytes(3, FieldCrypto.encryptField(
                    profile.correo(), profile.apellido(), salt, profile.nombre()));
            insert.setBytes(4, FieldCrypto.encryptField(
                    profile.correo(), profile.apellido(), salt, profile.apellido()));
            insert.setBytes(5, FieldCrypto.encryptField(
                    profile.correo(), profile.apellido(), salt, profile.telefono()));
            insert.setString(6, tier == null ? "FREE" : tier);
            insert.setBytes(7, salt);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("sin id generado para usuario");
                }
                return new UserId(keys.getLong(1), true, salt);
            }
        }
    }

    /** Usuarios existentes según el hash del correo; Optional.empty si no hay. */
    public Optional<Long> findUserIdByEmail(String correo) throws SQLException {
        try (Connection connection = connectionProvider.get()) {
            SchemaInitializer.ensureSchema(connection);
            return findIdByEmailHash(connection, FieldCrypto.emailHash(correo));
        }
    }

    /** Recupera el perfil descifrado; el apellido incorrecto NO permite abrir los campos. */
    public Optional<CustomerProfile> loadDecrypted(String correo, String apellido) throws SQLException {
        try (Connection connection = connectionProvider.get()) {
            SchemaInitializer.ensureSchema(connection);
            String emailHash = FieldCrypto.emailHash(correo);
            Optional<Long> id = findIdByEmailHash(connection, emailHash);
            if (id.isEmpty()) {
                return Optional.empty();
            }
            byte[] salt = readSalt(connection, id.get());
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT email_enc, first_name_enc, last_name_enc, phone_enc, salt "
                            + "FROM users WHERE id=?")) {
                query.setLong(1, id.get());
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new CustomerProfile(
                            FieldCrypto.decryptField(correo, apellido, salt, rs.getBytes("first_name_enc")),
                            FieldCrypto.decryptField(correo, apellido, salt, rs.getBytes("last_name_enc")),
                            FieldCrypto.decryptField(correo, apellido, salt, rs.getBytes("email_enc")),
                            FieldCrypto.decryptField(correo, apellido, salt, rs.getBytes("phone_enc"))));
                }
            }
        }
    }

    /** Diagnóstico/auditoría: SIEMPRE hash y ciphertext, jamás texto en claro. */
    public List<String> describeStoredUsers() throws SQLException {
        try (Connection connection = connectionProvider.get()) {
            SchemaInitializer.ensureSchema(connection);
            List<String> lines = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id, email_hash, tier FROM users ORDER BY id")) {
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        lines.add(String.format("id=%d email_hash=%s tier=%s",
                                rs.getLong("id"), rs.getString("email_hash"), rs.getString("tier")));
                    }
                }
            }
            return lines;
        }
    }

    private byte[] readSalt(Connection connection, long id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT salt FROM users WHERE id=?")) {
            query.setLong(1, id);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next() || rs.getBytes("salt") == null) {
                    throw new SQLException("usuario sin salt: imposible descifrar");
                }
                return rs.getBytes("salt");
            }
        }
    }

    private Optional<Long> findIdByEmailHash(Connection connection, String emailHash) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id FROM users WHERE email_hash=?")) {
            query.setString(1, emailHash);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }
}
