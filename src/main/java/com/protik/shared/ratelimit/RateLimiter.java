package com.protik.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Fixed-window rate limiting shared by all modules (ADR 0013). Counters live in
 * PostgreSQL, so every application instance sees the same numbers.
 *
 * <p>Typical use is <em>reserve, then refund</em>: count the attempt first with
 * {@link #acquire}, do the expensive work only if it was allowed, and {@link #refund}
 * on success. Counting first is what keeps a burst of simultaneous requests inside the limit.
 *
 * <p>Every method commits in its own transaction, so a caller's rollback (for example an
 * error response) does not erase the count.
 */
public interface RateLimiter {

    /** One counter: at most {@code max} attempts per {@code window} for {@code key}. */
    record Limit(String key, int max, Duration window) {
    }

    /**
     * Counts one attempt against every limit, atomically. The attempt is counted even when
     * it is over a limit, so continued hammering keeps the block in place for the window.
     *
     * @return empty if the attempt is within all limits, otherwise how long until the
     *         blocking limit with the longest wait clears
     */
    Optional<Duration> acquire(List<Limit> limits);

    /** Gives one attempt back to each key (never below zero). */
    void refund(List<String> keys);

    /** Forgets the keys entirely. */
    void reset(List<String> keys);
}
