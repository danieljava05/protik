package com.protik.identity.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for email tokens and the account facts the recovery flows need (ADR 0021). Callers own the transaction. */
@Repository
public class EmailTokenRepository {

    /** An account found by its email address. */
    public record Holder(UUID accountId, String username, String status, String email) {
    }

    /** A usable token, locked for update. */
    public record Token(UUID id, UUID accountId, String purpose) {
    }

    private final JdbcClient db;

    EmailTokenRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Holder> findByEmail(String email) {
        return db.sql("""
                SELECT a.id, a.username, a.status, li.value::text AS email
                FROM identity.login_identifiers li JOIN identity.accounts a ON a.id = li.account_id
                WHERE li.kind = 'EMAIL' AND li.value = CAST(:email AS citext)
                """)
                .param("email", email)
                .query((rs, n) -> new Holder(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("status"), rs.getString("email")))
                .optional();
    }

    public Optional<Holder> findById(UUID accountId) {
        return db.sql("""
                SELECT a.id, a.username, a.status, li.value::text AS email
                FROM identity.login_identifiers li JOIN identity.accounts a ON a.id = li.account_id
                WHERE li.kind = 'EMAIL' AND a.id = :id
                ORDER BY li.id LIMIT 1
                """)
                .param("id", accountId)
                .query((rs, n) -> new Holder(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("status"), rs.getString("email")))
                .optional();
    }

    public boolean emailVerified(UUID accountId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM identity.login_identifiers WHERE account_id = :id "
                        + "AND kind = 'EMAIL' AND verified_at IS NOT NULL)")
                .param("id", accountId).query(Boolean.class).single();
    }

    /** Ends the account's unused tokens of this purpose, so only the newest one works. */
    public void invalidateUnused(UUID accountId, String purpose) {
        db.sql("UPDATE identity.email_tokens SET used_at = now() WHERE account_id = :a AND purpose = :p AND used_at IS NULL")
                .param("a", accountId).param("p", purpose).update();
    }

    public void insert(UUID id, UUID accountId, String purpose, byte[] hash, Duration ttl) {
        db.sql("""
                INSERT INTO identity.email_tokens (id, account_id, purpose, token_hash, expires_at)
                VALUES (:id, :a, :p, :h, now() + make_interval(secs => :ttl))
                """)
                .param("id", id).param("a", accountId).param("p", purpose).param("h", hash)
                .param("ttl", ttl.toMillis() / 1000.0).update();
    }

    /** Finds an unused, unexpired token of the purpose and locks it, so two uses cannot both succeed. */
    public Optional<Token> lockUsable(byte[] hash, String purpose) {
        return db.sql("""
                SELECT id, account_id, purpose FROM identity.email_tokens
                WHERE token_hash = :h AND purpose = :p AND used_at IS NULL AND expires_at > now()
                FOR UPDATE
                """)
                .param("h", hash).param("p", purpose)
                .query((rs, n) -> new Token(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getString("purpose")))
                .optional();
    }

    public void markUsed(UUID id) {
        db.sql("UPDATE identity.email_tokens SET used_at = now() WHERE id = :id").param("id", id).update();
    }

    public void markEmailVerified(UUID accountId) {
        db.sql("UPDATE identity.login_identifiers SET verified_at = now() "
                + "WHERE account_id = :id AND kind = 'EMAIL' AND verified_at IS NULL").param("id", accountId).update();
    }

    public void replacePassword(UUID accountId, String hash) {
        db.sql("UPDATE identity.password_credentials SET password_hash = :h, updated_at = now() WHERE account_id = :id")
                .param("h", hash).param("id", accountId).update();
    }

    public int purgeOlderThan(Duration age) {
        return db.sql("DELETE FROM identity.email_tokens WHERE expires_at < now() - make_interval(secs => :age)")
                .param("age", age.toMillis() / 1000.0).update();
    }
}
