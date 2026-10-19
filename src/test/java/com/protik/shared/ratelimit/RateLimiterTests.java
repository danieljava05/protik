package com.protik.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import com.protik.shared.ratelimit.RateLimiter.Limit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The algorithm of ADR 0013, independent of what it is used to protect. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateLimiterTests {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    @Autowired
    RateLimiter limiter;
    @Autowired
    RateLimiterService service;
    @Autowired
    JdbcClient db;
    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM shared.rate_limits").update();
    }

    private static String key() {
        return RateLimitKeys.of("test", UUID.randomUUID().toString());
    }

    private boolean allowed(String key, int max) {
        return limiter.acquire(List.of(new Limit(key, max, WINDOW))).isEmpty();
    }

    private int count(String key) {
        return db.sql("SELECT count FROM shared.rate_limits WHERE key = :k").param("k", key)
                .query(Integer.class).optional().orElse(0);
    }

    @Test
    void exactlyTheLimitIsAllowedThenEverythingIsBlocked() {
        String k = key();
        for (int i = 1; i <= 5; i++) {
            assertThat(allowed(k, 5)).as("attempt %d of 5", i).isTrue();
        }
        assertThat(allowed(k, 5)).as("attempt 6").isFalse();
        assertThat(allowed(k, 5)).as("attempt 7").isFalse();
    }

    @Test
    void theBlockReportsHowLongToWait() {
        String k = key();
        allowed(k, 1);
        var wait = limiter.acquire(List.of(new Limit(k, 1, WINDOW))).orElseThrow();
        assertThat(wait).isBetween(Duration.ofMinutes(14), WINDOW.plusSeconds(1));
    }

    @Test
    void theLongestWaitAmongBlockingLimitsIsReported() {
        String shortKey = key();
        String longKey = key();
        limiter.acquire(List.of(new Limit(shortKey, 1, Duration.ofMinutes(1)), new Limit(longKey, 1, Duration.ofHours(1))));
        var wait = limiter.acquire(List.of(new Limit(shortKey, 1, Duration.ofMinutes(1)),
                new Limit(longKey, 1, Duration.ofHours(1)))).orElseThrow();
        assertThat(wait).isGreaterThan(Duration.ofMinutes(50));
    }

    @Test
    void everyKeyInOneCallIsCountedEvenWhenAnotherBlocks() {
        String a = key();
        String b = key();
        allowed(a, 1);
        limiter.acquire(List.of(new Limit(a, 1, WINDOW), new Limit(b, 10, WINDOW)));
        assertThat(count(b)).isEqualTo(1);
    }

    @Test
    void aRefundGivesAnAttemptBackAndNeverGoesNegative() {
        String k = key();
        allowed(k, 5);
        allowed(k, 5);
        limiter.refund(List.of(k));
        assertThat(count(k)).isEqualTo(1);
        limiter.refund(List.of(k));
        limiter.refund(List.of(k));
        limiter.refund(List.of(k));
        assertThat(count(k)).isZero();
        limiter.refund(List.of(key())); // unknown key: nothing happens
    }

    @Test
    void resetForgetsTheKey() {
        String k = key();
        allowed(k, 5);
        limiter.reset(List.of(k));
        assertThat(count(k)).isZero();
        assertThat(db.sql("SELECT count(*) FROM shared.rate_limits").query(Long.class).single()).isZero();
    }

    @Test
    void aNewWindowStartsFromScratch() {
        String k = key();
        for (int i = 0; i < 5; i++) {
            allowed(k, 5);
        }
        assertThat(allowed(k, 5)).isFalse();
        db.sql("UPDATE shared.rate_limits SET window_start = now() - interval '16 minutes'").update();
        assertThat(allowed(k, 5)).as("the old window ended").isTrue();
        assertThat(count(k)).isEqualTo(1);
    }

    @Test
    void differentKeysDoNotShareACounter() {
        String a = key();
        String b = key();
        for (int i = 0; i < 3; i++) {
            allowed(a, 3);
        }
        assertThat(allowed(a, 3)).isFalse();
        assertThat(allowed(b, 3)).isTrue();
    }

    @Test
    void aFailedCallerTransactionDoesNotEraseTheCount() {
        String k = key();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(tx -> {
                limiter.acquire(List.of(new Limit(k, 5, WINDOW)));
                throw new IllegalStateException("the request failed after counting");
            });
        } catch (IllegalStateException expected) {
            // the request rolled back...
        }
        assertThat(count(k)).as("...but the attempt stays counted").isEqualTo(1);
    }

    @Test
    void oneHundredSimultaneousCallersGetExactlyTheLimit() throws Exception {
        String k = key();
        int callers = 100;
        int max = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return allowed(k, max);
            }));
        }
        go.countDown();
        long allowedCount = 0;
        for (var f : results) {
            if (f.get(120, TimeUnit.SECONDS)) {
                allowedCount++;
            }
        }
        pool.shutdownNow();

        assertThat(allowedCount).as("no burst slips past the limit").isEqualTo(max);
        assertThat(count(k)).isEqualTo(callers);
    }

    @Test
    void overlappingMultiKeyCallsInDifferentOrderDoNotDeadlock() throws Exception {
        String a = key();
        String b = key();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean forward = i % 2 == 0;
            futures.add(pool.submit(() -> limiter.acquire(forward
                    ? List.of(new Limit(a, 100000, WINDOW), new Limit(b, 100000, WINDOW))
                    : List.of(new Limit(b, 100000, WINDOW), new Limit(a, 100000, WINDOW)))));
        }
        for (var f : futures) {
            f.get(60, TimeUnit.SECONDS); // a deadlock would time out here
        }
        pool.shutdownNow();
        assertThat(count(a)).isEqualTo(200);
        assertThat(count(b)).isEqualTo(200);
    }

    @Test
    void thePurgeRemovesOnlyLongFinishedWindows() {
        String old = key();
        String recent = key();
        allowed(old, 5);
        allowed(recent, 5);
        db.sql("UPDATE shared.rate_limits SET window_start = now() - interval '2 days' WHERE key = :k")
                .param("k", old).update();
        service.purge();
        assertThat(count(old)).isZero();
        assertThat(count(recent)).isEqualTo(1);
    }

    @Test
    void keysHideTheirInputsAndDoNotCollideOnConcatenation() {
        String k = RateLimitKeys.of("login-ip", "203.0.113.7");
        assertThat(k).matches("[0-9a-f]{64}").doesNotContain("203");
        assertThat(RateLimitKeys.of("s", "a", "bc")).isNotEqualTo(RateLimitKeys.of("s", "ab", "c"));
        assertThat(RateLimitKeys.of("s", " Ada@Example.com ")).as("case and whitespace are normalised")
                .isEqualTo(RateLimitKeys.of("s", "ada@example.com"));
        assertThat(RateLimitKeys.of("login-ip", "x")).isNotEqualTo(RateLimitKeys.of("register-ip", "x"));
    }
}
