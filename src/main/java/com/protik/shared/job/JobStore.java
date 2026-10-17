package com.protik.shared.job;

import com.protik.shared.id.Ids;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * All SQL for the job queue. Every state change after a claim is fenced by the
 * attempt number the caller claimed with (ADR 0008 decision 2), and every time
 * comparison uses the database clock.
 */
class JobStore {

    /** A job a worker now owns. */
    record ClaimedJob(UUID id, String type, String payloadJson, int attempt, int maxAttempts) {
    }

    private final JdbcClient db;

    JobStore(JdbcClient db) {
        this.db = db;
    }

    UUID enqueue(String type, String payloadJson, Duration delay, int maxAttempts) {
        UUID id = Ids.newId();
        db.sql("""
                INSERT INTO shared.jobs (id, type, payload, max_attempts, run_at)
                VALUES (:id, :type, CAST(:payload AS jsonb), :maxAttempts, now() + make_interval(secs => :delay))
                """)
                .param("id", id)
                .param("type", type)
                .param("payload", payloadJson)
                .param("maxAttempts", maxAttempts)
                .param("delay", seconds(delay))
                .update();
        return id;
    }

    /**
     * Atomically takes up to {@code limit} due jobs of the given types.
     * {@code SKIP LOCKED} makes concurrent claimers pass over rows another
     * claimer holds instead of waiting, so no job is claimed twice and no
     * worker blocks on another.
     */
    List<ClaimedJob> claim(Collection<String> types, int limit, Duration lease) {
        if (types.isEmpty()) {
            return List.of();
        }
        return db.sql("""
                WITH due AS (
                    SELECT id FROM shared.jobs
                    WHERE status = 'QUEUED' AND run_at <= now() AND type IN (:types)
                    ORDER BY run_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit)
                UPDATE shared.jobs j
                SET status = 'RUNNING', attempts = j.attempts + 1,
                    lease_expires_at = now() + make_interval(secs => :lease)
                FROM due WHERE j.id = due.id
                RETURNING j.id, j.type, j.payload::text AS payload, j.attempts, j.max_attempts
                """)
                .param("types", types)
                .param("limit", limit)
                .param("lease", seconds(lease))
                .query((rs, n) -> new ClaimedJob(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("payload"), rs.getInt("attempts"), rs.getInt("max_attempts")))
                .list();
    }

    /** @return false if the caller no longer owns the job */
    boolean complete(UUID id, int attempt) {
        return db.sql("""
                UPDATE shared.jobs
                SET status = 'DONE', lease_expires_at = NULL, last_error = NULL, finished_at = now()
                WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                """)
                .param("id", id).param("attempt", attempt).update() == 1;
    }

    /**
     * Records a failed attempt: back to QUEUED after {@code retryDelay}, or DEAD
     * if the attempts are used up.
     *
     * @return false if the caller no longer owns the job
     */
    boolean fail(UUID id, int attempt, String error, Duration retryDelay) {
        return db.sql("""
                UPDATE shared.jobs
                SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'QUEUED' END,
                    run_at = CASE WHEN attempts >= max_attempts THEN run_at
                                  ELSE now() + make_interval(secs => :delay) END,
                    finished_at = CASE WHEN attempts >= max_attempts THEN now() END,
                    lease_expires_at = NULL, last_error = :error
                WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                """)
                .param("id", id).param("attempt", attempt)
                .param("error", error).param("delay", seconds(retryDelay)).update() == 1;
    }

    /** @return false if the caller no longer owns the job */
    boolean extendLease(UUID id, int attempt, Duration lease) {
        return db.sql("""
                UPDATE shared.jobs SET lease_expires_at = now() + make_interval(secs => :lease)
                WHERE id = :id AND status = 'RUNNING' AND attempts = :attempt
                """)
                .param("id", id).param("attempt", attempt).param("lease", seconds(lease)).update() == 1;
    }

    /**
     * Takes jobs back from workers that went silent. Counts as an attempt (it
     * was already counted at claim), so a job that kills its worker every time
     * ends up DEAD instead of looping.
     *
     * @return how many jobs were recovered
     */
    int requeueExpired() {
        return db.sql("""
                UPDATE shared.jobs
                SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'QUEUED' END,
                    finished_at = CASE WHEN attempts >= max_attempts THEN now() END,
                    lease_expires_at = NULL,
                    last_error = 'lease expired (worker stalled or crashed)'
                WHERE status = 'RUNNING' AND lease_expires_at < now()
                """).update();
    }

    /** Deletes DONE jobs older than the retention period. DEAD jobs are kept for an operator. */
    int purgeDone(Duration retention) {
        return db.sql("""
                DELETE FROM shared.jobs
                WHERE status = 'DONE' AND finished_at < now() - make_interval(secs => :retention)
                """)
                .param("retention", seconds(retention)).update();
    }

    private static double seconds(Duration d) {
        return d.toMillis() / 1000.0;
    }
}
