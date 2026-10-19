package com.protik.shared.ratelimit;

import java.time.Duration;
import java.util.Collection;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL for {@code shared.rate_limits}. Time comparisons use the database clock. */
class RateLimitStore {

    /** The counter after this attempt, and how long until its window ends. */
    record Counter(int count, double secondsLeft) {
    }

    private final JdbcClient db;

    RateLimitStore(JdbcClient db) {
        this.db = db;
    }

    /**
     * Counts one attempt: starts a new window if the old one ended, otherwise increments.
     * A single atomic statement, so simultaneous attempts are counted exactly once each.
     */
    Counter increment(String key, Duration window) {
        double seconds = window.toMillis() / 1000.0;
        return db.sql("""
                INSERT INTO shared.rate_limits AS r (key, window_start, count)
                VALUES (:key, now(), 1)
                ON CONFLICT (key) DO UPDATE SET
                    window_start = CASE WHEN r.window_start + make_interval(secs => :window) <= now()
                                        THEN now() ELSE r.window_start END,
                    count = CASE WHEN r.window_start + make_interval(secs => :window) <= now()
                                 THEN 1 ELSE r.count + 1 END
                RETURNING count,
                    EXTRACT(EPOCH FROM (window_start + make_interval(secs => :window) - now())) AS seconds_left
                """)
                .param("key", key).param("window", seconds)
                .query((rs, n) -> new Counter(rs.getInt("count"), rs.getDouble("seconds_left")))
                .single();
    }

    void decrement(String key) {
        db.sql("UPDATE shared.rate_limits SET count = GREATEST(count - 1, 0) WHERE key = :key")
                .param("key", key).update();
    }

    void delete(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        db.sql("DELETE FROM shared.rate_limits WHERE key IN (:keys)").param("keys", keys).update();
    }

    int purgeOlderThan(Duration age) {
        return db.sql("DELETE FROM shared.rate_limits WHERE window_start < now() - make_interval(secs => :age)")
                .param("age", age.toMillis() / 1000.0).update();
    }
}
