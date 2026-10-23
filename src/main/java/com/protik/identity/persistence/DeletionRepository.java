package com.protik.identity.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for account deletion (ADR 0020). Callers own the transaction. */
@Repository
public class DeletionRepository {

    public record Request(UUID id, UUID accountId, boolean completed) {
    }

    private final JdbcClient db;

    DeletionRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<String> passwordHash(UUID accountId) {
        return db.sql("SELECT password_hash FROM identity.password_credentials WHERE account_id = :id")
                .param("id", accountId).query(String.class).optional();
    }

    /** Locks the account row so two deletion requests cannot interleave. @return its status, if it exists */
    public Optional<String> lockAccountStatus(UUID accountId) {
        return db.sql("SELECT status FROM identity.accounts WHERE id = :id FOR UPDATE")
                .param("id", accountId).query(String.class).optional();
    }

    public void markPending(UUID accountId) {
        db.sql("UPDATE identity.accounts SET status = 'DELETION_PENDING' WHERE id = :id").param("id", accountId).update();
    }

    public void revokeSessions(UUID accountId) {
        db.sql("UPDATE identity.session_families SET revoked_at = now(), revoked_reason = 'ACCOUNT_DELETION' "
                + "WHERE account_id = :id AND revoked_at IS NULL").param("id", accountId).update();
    }

    public void insertRequest(UUID id, UUID accountId) {
        db.sql("INSERT INTO identity.deletion_requests (id, account_id) VALUES (:id, :a)")
                .param("id", id).param("a", accountId).update();
    }

    public Optional<Request> findByAccount(UUID accountId) {
        return db.sql("SELECT id, account_id, completed_at IS NOT NULL AS done FROM identity.deletion_requests WHERE account_id = :a")
                .param("a", accountId).query((rs, n) -> request(rs)).optional();
    }

    /** Locks the request so concurrent acknowledgements are counted one at a time. */
    public Optional<Request> lockRequest(UUID id) {
        return db.sql("SELECT id, account_id, completed_at IS NOT NULL AS done FROM identity.deletion_requests WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, n) -> request(rs)).optional();
    }

    public void insertAck(UUID requestId, String module) {
        db.sql("INSERT INTO identity.deletion_acks (request_id, module) VALUES (:r, :m) ON CONFLICT DO NOTHING")
                .param("r", requestId).param("m", module).update();
    }

    public int countAcks(UUID requestId) {
        return db.sql("SELECT count(*) FROM identity.deletion_acks WHERE request_id = :r").param("r", requestId)
                .query(Integer.class).single();
    }

    /** Deletes the account; credentials, identifiers, profile, roles and sessions go with it by cascade. */
    public void deleteAccount(UUID accountId) {
        db.sql("DELETE FROM identity.accounts WHERE id = :id").param("id", accountId).update();
    }

    public void complete(UUID requestId) {
        db.sql("UPDATE identity.deletion_requests SET completed_at = now() WHERE id = :id").param("id", requestId).update();
    }

    /** Requests that are not complete and have waited longer than {@code age}. */
    public List<Request> overdue(Duration age) {
        return db.sql("""
                SELECT id, account_id, false AS done FROM identity.deletion_requests
                WHERE completed_at IS NULL AND requested_at < now() - make_interval(secs => :age)
                """)
                .param("age", age.toMillis() / 1000.0).query((rs, n) -> request(rs)).list();
    }

    private static Request request(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Request(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getBoolean("done"));
    }
}
