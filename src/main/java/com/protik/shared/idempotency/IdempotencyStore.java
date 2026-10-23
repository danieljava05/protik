package com.protik.shared.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL for {@code shared.idempotency_keys}. Time comparisons use the database clock. */
class IdempotencyStore {

    record Stored(String endpoint, byte[] fingerprint, Integer responseStatus, String responseBody) {
    }

    private final JdbcClient db;

    IdempotencyStore(JdbcClient db) {
        this.db = db;
    }

    /**
     * Tries to become the owner of the key. If another transaction holds the same
     * key and has not finished, this statement waits on the unique index until it
     * commits or rolls back; that wait is what serialises concurrent duplicates.
     *
     * @return true if this transaction now owns the key (new, or an expired one reset)
     */
    boolean claim(UUID scope, String key, String endpoint, byte[] fingerprint, Duration ttl) {
        return db.sql("""
                INSERT INTO shared.idempotency_keys (user_id, key, endpoint, request_hash, expires_at)
                VALUES (:scope, :key, :endpoint, :fingerprint, now() + make_interval(secs => :ttl))
                ON CONFLICT (user_id, key) DO UPDATE
                SET endpoint = EXCLUDED.endpoint, request_hash = EXCLUDED.request_hash,
                    response_status = NULL, response_body = NULL,
                    created_at = now(), expires_at = EXCLUDED.expires_at
                WHERE shared.idempotency_keys.expires_at < now()
                RETURNING 1
                """)
                .param("scope", scope).param("key", key).param("endpoint", endpoint)
                .param("fingerprint", fingerprint).param("ttl", ttl.toMillis() / 1000.0)
                .query(Integer.class).optional().isPresent();
    }

    void saveResponse(UUID scope, String key, int status, String bodyJson) {
        db.sql("""
                UPDATE shared.idempotency_keys
                SET response_status = :status, response_body = CAST(:body AS jsonb)
                WHERE user_id = :scope AND key = :key
                """)
                .param("status", status).param("body", bodyJson).param("scope", scope).param("key", key)
                .update();
    }

    Optional<Stored> find(UUID scope, String key) {
        return db.sql("""
                SELECT endpoint, request_hash, response_status, response_body::text AS body
                FROM shared.idempotency_keys WHERE user_id = :scope AND key = :key
                """)
                .param("scope", scope).param("key", key)
                .query((rs, n) -> new Stored(rs.getString("endpoint"), rs.getBytes("request_hash"),
                        (Integer) rs.getObject("response_status"), rs.getString("body")))
                .optional();
    }

    int deleteForScope(UUID scope) {
        return db.sql("DELETE FROM shared.idempotency_keys WHERE user_id = :u").param("u", scope).update();
    }

    int purgeExpired() {
        return db.sql("DELETE FROM shared.idempotency_keys WHERE expires_at < now()").update();
    }
}
