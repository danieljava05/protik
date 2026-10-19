package com.protik.identity.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL for the identity schema (ADR 0010). Callers own the transaction. */
@Repository
public class AccountRepository {

    /** What login needs to decide. {@code passwordHash} is never sent anywhere. */
    public record LoginRecord(UUID accountId, String status, String passwordHash) {
    }

    public record ProfileRecord(UUID accountId, String username, String displayName) {
    }

    private final JdbcClient db;

    AccountRepository(JdbcClient db) {
        this.db = db;
    }

    public void insertAccount(UUID id, String username) {
        db.sql("INSERT INTO identity.accounts (id, username) VALUES (:id, :username)")
                .param("id", id).param("username", username).update();
    }

    public void insertEmail(UUID id, UUID accountId, String email) {
        db.sql("INSERT INTO identity.login_identifiers (id, account_id, kind, value) VALUES (:id, :account, 'EMAIL', :value)")
                .param("id", id).param("account", accountId).param("value", email).update();
    }

    public void insertPassword(UUID accountId, String passwordHash) {
        db.sql("INSERT INTO identity.password_credentials (account_id, password_hash) VALUES (:account, :hash)")
                .param("account", accountId).param("hash", passwordHash).update();
    }

    public void insertProfile(UUID accountId, String displayName) {
        db.sql("INSERT INTO identity.profiles (account_id, display_name) VALUES (:account, :name)")
                .param("account", accountId).param("name", displayName).update();
    }

    /** Finds an account by username or email, both case-insensitive (citext). */
    public Optional<LoginRecord> findForLogin(String identifier) {
        return db.sql("""
                SELECT a.id, a.status, p.password_hash
                FROM identity.accounts a
                JOIN identity.password_credentials p ON p.account_id = a.id
                WHERE a.username = CAST(:identifier AS citext)
                   OR a.id IN (SELECT li.account_id FROM identity.login_identifiers li
                               WHERE li.kind = 'EMAIL' AND li.value = CAST(:identifier AS citext))
                LIMIT 1
                """)
                .param("identifier", identifier)
                .query((rs, n) -> new LoginRecord(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("password_hash")))
                .optional();
    }

    public Optional<ProfileRecord> findProfile(UUID accountId) {
        return db.sql("""
                SELECT a.id, a.username, pr.display_name
                FROM identity.accounts a JOIN identity.profiles pr ON pr.account_id = a.id
                WHERE a.id = :id
                """)
                .param("id", accountId)
                .query((rs, n) -> new ProfileRecord(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("display_name")))
                .optional();
    }
}
