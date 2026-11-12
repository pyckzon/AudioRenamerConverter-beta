package com.casl.mp3converter.saas.auth;

import com.casl.mp3converter.core.license.FieldCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * FASE 8.2 — Persistencia de usuarios del SaaS (schema {@code saas.users}).
 * Mismas reglas de privacidad que v2: el correo nunca viaja plano — se guarda
 * su HMAC ({@code email_hash}, único) y el resto cifrado con AES-256-GCM
 * (sal=correo normalizado, pepper=apellido). La password SOLO como BCrypt.
 */
@Repository
public class SaasUserRepository {

    private final JdbcTemplate jdbc;

    public SaasUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SaasUser(long id, String emailHash, byte[] salt) { }

    /** Alta con password BCrypt; devuelve el id, o vacío si el correo ya existe. */
    public Optional<Long> register(String correo, String apellido,
                                   String passwordBcrypt, String nombre) {
        String emailHash = FieldCrypto.emailHash(correo);
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM saas.users WHERE email_hash = ?", Long.class, emailHash);
        if (!existing.isEmpty()) {
            return Optional.empty();
        }
        byte[] salt = FieldCrypto.newSalt();
        jdbc.update("""
                        INSERT INTO saas.users (email_hash, email_enc, password_hash,
                                                first_name_enc, last_name_enc, salt)
                        VALUES (?,?,?,?,?,?)""",
                emailHash,
                FieldCrypto.encryptField(correo, apellido, salt, correo),
                passwordBcrypt,
                FieldCrypto.encryptField(correo, apellido, salt, nombre),
                FieldCrypto.encryptField(correo, apellido, salt, apellido),
                salt);
        return Optional.of(idOf(emailHash));
    }

    /** Para el login: (id, hash de password BCrypt, salt de cifrado) por email_hash. */
    public record Credentials(long id, String passwordHash, byte[] salt) { }

    public Optional<Credentials> credentialsByEmail(String correo) {
        String emailHash = FieldCrypto.emailHash(correo);
        return jdbc.query("""
                        SELECT id, password_hash, salt FROM saas.users WHERE email_hash = ?""",
                (rs, i) -> new Credentials(rs.getLong("id"),
                        rs.getString("password_hash"), rs.getBytes("salt")),
                emailHash).stream().findFirst();
    }

    public String emailEncrypted(long id, String correo, String apellido, byte[] salt) {
        // descifra el correo almacenado (utilidad de verificación de round-trip):
        List<byte[]> rows = jdbc.query(
                "SELECT email_enc FROM saas.users WHERE id = ?",
                (rs, i) -> rs.getBytes(1), id);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return null;
        }
        return FieldCrypto.decryptField(correo, apellido, salt, rows.getFirst());
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM saas.users", Long.class);
        return n == null ? 0L : n;
    }

    public Timestamp createdAt(long id) {
        return jdbc.queryForObject(
                "SELECT created_at FROM saas.users WHERE id = ?",
                Timestamp.class, id);
    }

    private long idOf(String emailHash) {
        return jdbc.queryForObject(
                "SELECT id FROM saas.users WHERE email_hash = ?", Long.class, emailHash);
    }

    /** utilidad de tests: fecha "ahora" consistente. */
    static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
