package com.protik.identity.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for sessions (families) and their refresh tokens (ADR 0012). Every time
 * comparison happens in SQL against the database clock. Callers own the transaction.
 */
@Repository
public class SessionRepository {

    /** The family a presented token hash belongs to (read without a lock). */
    public record TokenOwner(UUID tokenId, UUID familyId) {
    }

    /** The state of a token and its session, read while holding the family lock. */
    public record TokenState(UUID tokenId, UUID accountId, boolean familyRevoked, boolean rotated,
            double secondsSinceRotation, boolean tokenExpired, boolean familyTooOld, String accountStatus) {
    }

    private final JdbcClient db;

    SessionRepository(JdbcClient db) {
        this.db = db;
    }

    public void insertFamily(UUID id, UUID accountId) {
        db.sql("INSERT INTO identity.session_families (id, account_id) VALUES (:id, :account)")
                .param("id", id).param("account", accountId).update();
    }

    public void insertToken(UUID id, UUID familyId, byte[] tokenHash, Duration ttl) {
        db.sql("""
                INSERT INTO identity.refresh_tokens (id, family_id, token_hash, expires_at)
                VALUES (:id, :family, :hash, now() + make_interval(secs => :ttl))
                """)
                .param("id", id).param("family", familyId).param("hash", tokenHash)
                .param("ttl", ttl.toMillis() / 1000.0).update();
    }

    public Optional<TokenOwner> findOwner(byte[] tokenHash) {
        return db.sql("SELECT id, family_id FROM identity.refresh_tokens WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .query((rs, n) -> new TokenOwner(rs.getObject("id", UUID.class), rs.getObject("family_id", UUID.class)))
                .optional();
    }

    /**
     * Locks the family so concurrent refreshes of its tokens run one after another.
     * Must be called inside a transaction, before {@link #loadState}.
     */
    public void lockFamily(UUID familyId) {
        db.sql("SELECT id FROM identity.session_families WHERE id = :id FOR UPDATE")
                .param("id", familyId).query(UUID.class).optional();
    }

    public Optional<TokenState> loadState(UUID familyId, byte[] tokenHash, Duration sessionMaxAge) {
        return db.sql("""
                SELECT t.id AS token_id, f.account_id,
                       f.revoked_at IS NOT NULL AS family_revoked,
                       t.rotated_at IS NOT NULL AS rotated,
                       COALESCE(EXTRACT(EPOCH FROM (now() - t.rotated_at)), 0) AS since_rotation,
                       t.expires_at < now() AS token_expired,
                       f.created_at + make_interval(secs => :maxAge) < now() AS family_too_old,
                       a.status AS account_status
                FROM identity.refresh_tokens t
                JOIN identity.session_families f ON f.id = t.family_id
                JOIN identity.accounts a ON a.id = f.account_id
                WHERE t.token_hash = :hash AND t.family_id = :family
                """)
                .param("hash", tokenHash).param("family", familyId)
                .param("maxAge", sessionMaxAge.toMillis() / 1000.0)
                .query((rs, n) -> new TokenState(rs.getObject("token_id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getBoolean("family_revoked"),
                        rs.getBoolean("rotated"), rs.getDouble("since_rotation"), rs.getBoolean("token_expired"),
                        rs.getBoolean("family_too_old"), rs.getString("account_status")))
                .optional();
    }

    /** @return false if the token was already rotated (someone else won) */
    public boolean markRotated(UUID tokenId) {
        return db.sql("UPDATE identity.refresh_tokens SET rotated_at = now() WHERE id = :id AND rotated_at IS NULL")
                .param("id", tokenId).update() == 1;
    }

    public void revokeFamily(UUID familyId, String reason) {
        db.sql("""
                UPDATE identity.session_families SET revoked_at = now(), revoked_reason = :reason
                WHERE id = :id AND revoked_at IS NULL
                """)
                .param("id", familyId).param("reason", reason).update();
    }

    /** Revokes the session that owns this token, whatever the token's state. */
    public int revokeByTokenHash(byte[] tokenHash, String reason) {
        return db.sql("""
                UPDATE identity.session_families SET revoked_at = now(), revoked_reason = :reason
                WHERE id = (SELECT family_id FROM identity.refresh_tokens WHERE token_hash = :hash)
                  AND revoked_at IS NULL
                """)
                .param("hash", tokenHash).param("reason", reason).update();
    }

    public int revokeAllForAccount(UUID accountId, String reason) {
        return db.sql("""
                UPDATE identity.session_families SET revoked_at = now(), revoked_reason = :reason
                WHERE account_id = :account AND revoked_at IS NULL
                """)
                .param("account", accountId).param("reason", reason).update();
    }

    /**
     * Deletes sessions that were revoked, or that passed their maximum age, more than
     * {@code retention} ago. Tokens go with them (ON DELETE CASCADE).
     */
    public int purge(Duration retention, Duration sessionMaxAge) {
        return db.sql("""
                DELETE FROM identity.session_families
                WHERE revoked_at < now() - make_interval(secs => :retention)
                   OR created_at < now() - make_interval(secs => :retention + :maxAge)
                """)
                .param("retention", retention.toMillis() / 1000.0)
                .param("maxAge", sessionMaxAge.toMillis() / 1000.0).update();
    }
}
