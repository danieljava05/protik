package com.protik.shared.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency claims of ADR 0003 / ADR 0008, proven against a real PostgreSQL
 * with real threads (CONTRIBUTING: not by reasoning). Workers are disabled so
 * the tests are the only actors.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobStoreConcurrencyTests {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    JobStore store;
    @Autowired
    JdbcClient db;
    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM shared.jobs").update();
    }

    private UUID enqueue(String type) {
        return store.enqueue(type, "{}", Duration.ZERO, 5);
    }

    private String status(UUID id) {
        return db.sql("SELECT status FROM shared.jobs WHERE id = :id").param("id", id).query(String.class).single();
    }

    private int attempts(UUID id) {
        return db.sql("SELECT attempts FROM shared.jobs WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    private void waitForLeaseToExpire(UUID id) throws InterruptedException {
        // database clock, not ours
        for (int i = 0; i < 100; i++) {
            boolean expired = db.sql("SELECT lease_expires_at < now() FROM shared.jobs WHERE id = :id")
                    .param("id", id).query(Boolean.class).single();
            if (expired) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("lease did not expire in time");
    }

    @Test
    void noJobIsClaimedTwiceUnderHeavyContention() throws Exception {
        int jobs = 600;
        int threads = 16;
        for (int i = 0; i < jobs; i++) {
            enqueue("t");
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        var claimedIds = new ConcurrentHashMap<UUID, Integer>();      // id -> times claimed
        var threadsThatWorked = ConcurrentHashMap.<Integer>newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadNo = t;
            futures.add(pool.submit((Callable<Void>) () -> {
                go.await();
                int emptyInARow = 0;
                while (emptyInARow < 2) {
                    var batch = store.claim(Set.of("t"), 3, LEASE);
                    if (batch.isEmpty()) {
                        emptyInARow++;
                    } else {
                        emptyInARow = 0;
                        threadsThatWorked.add(threadNo);
                        batch.forEach(j -> claimedIds.merge(j.id(), 1, Integer::sum));
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(claimedIds).as("every job claimed").hasSize(jobs);
        assertThat(claimedIds.values()).as("each job claimed exactly once").allMatch(n -> n == 1);
        assertThat(threadsThatWorked.size()).as("work was really contended, not done by one thread")
                .isGreaterThan(1);
        assertThat(db.sql("SELECT count(*) FROM shared.jobs WHERE status = 'RUNNING' AND attempts = 1")
                .query(Long.class).single()).isEqualTo(jobs);
    }

    @Test
    void skipLockedPassesOverARowAnotherTransactionHolds() throws Exception {
        UUID first = enqueue("t");
        Thread.sleep(5); // distinct run_at so the order is deterministic
        UUID second = enqueue("t");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // Holder: locks the oldest job inside an open transaction.
        Future<UUID> holder = pool.submit(() -> new TransactionTemplate(txManager).execute(tx -> {
            UUID id = db.sql("SELECT id FROM shared.jobs WHERE status = 'QUEUED' ORDER BY run_at, id LIMIT 1 FOR UPDATE")
                    .query(UUID.class).single();
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return id;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // Claimer must NOT wait for the held row; it takes the next one right away.
        var claimed = pool.submit(() -> store.claim(Set.of("t"), 1, LEASE)).get(5, TimeUnit.SECONDS);
        assertThat(claimed).extracting(JobStore.ClaimedJob::id).containsExactly(second);
        assertThat(store.claim(Set.of("t"), 1, LEASE)).as("the held job is skipped, not awaited").isEmpty();

        release.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo(first);
        assertThat(store.claim(Set.of("t"), 1, LEASE)).extracting(JobStore.ClaimedJob::id).containsExactly(first);
        pool.shutdownNow();
    }

    @Test
    void expiredLeaseIsRecoveredAndTheStaleWorkerIsFenced() throws Exception {
        UUID id = enqueue("t");
        var stale = store.claim(Set.of("t"), 1, Duration.ofMillis(300)).getFirst();
        assertThat(stale.attempt()).isEqualTo(1);

        waitForLeaseToExpire(id);
        assertThat(store.requeueExpired()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(db.sql("SELECT last_error FROM shared.jobs WHERE id = :id").param("id", id)
                .query(String.class).single()).contains("lease expired");

        var fresh = store.claim(Set.of("t"), 1, LEASE).getFirst();
        assertThat(fresh.attempt()).isEqualTo(2);

        // The first worker wakes up late. None of its writes may take effect.
        assertThat(store.complete(id, stale.attempt())).as("stale complete").isFalse();
        assertThat(store.fail(id, stale.attempt(), "stale failure", Duration.ZERO)).as("stale fail").isFalse();
        assertThat(store.extendLease(id, stale.attempt(), LEASE)).as("stale extend").isFalse();
        assertThat(status(id)).isEqualTo("RUNNING");
        assertThat(attempts(id)).isEqualTo(2);

        assertThat(store.complete(id, fresh.attempt())).isTrue();
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(store.complete(id, fresh.attempt())).as("second complete is a no-op").isFalse();
    }

    @Test
    void anExtendedLeaseIsNotRecovered() throws Exception {
        UUID id = enqueue("t");
        var job = store.claim(Set.of("t"), 1, Duration.ofMillis(400)).getFirst();
        assertThat(store.extendLease(id, job.attempt(), LEASE)).isTrue();
        Thread.sleep(700);
        assertThat(store.requeueExpired()).isZero();
        assertThat(status(id)).isEqualTo("RUNNING");
    }

    @Test
    void aJobThatKeepsKillingItsWorkerEndsDeadInsteadOfLoopingForever() throws Exception {
        UUID id = store.enqueue("t", "{}", Duration.ZERO, 2);
        for (int attempt = 1; attempt <= 2; attempt++) {
            store.claim(Set.of("t"), 1, Duration.ofMillis(100));
            waitForLeaseToExpire(id);
            assertThat(store.requeueExpired()).isEqualTo(1);
        }
        assertThat(status(id)).isEqualTo("DEAD");
        assertThat(db.sql("SELECT finished_at IS NOT NULL FROM shared.jobs WHERE id = :id").param("id", id)
                .query(Boolean.class).single()).isTrue();
        assertThat(store.claim(Set.of("t"), 1, LEASE)).isEmpty();
    }

    @Test
    void manyRecoverersNeverRecoverTheSameJobTwice() throws Exception {
        int jobs = 50;
        for (int i = 0; i < jobs; i++) {
            enqueue("t");
        }
        store.claim(Set.of("t"), jobs, Duration.ofMillis(100));
        Thread.sleep(300);

        int recoverers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(recoverers);
        CountDownLatch go = new CountDownLatch(1);
        var total = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < recoverers; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                total.addAndGet(store.requeueExpired());
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(total.get()).as("each expired job recovered exactly once").isEqualTo(jobs);
        assertThat(db.sql("SELECT count(*) FROM shared.jobs WHERE status = 'QUEUED' AND attempts = 1")
                .query(Long.class).single()).isEqualTo(jobs);
    }

    @Test
    void failureBacksOffThenParksTheJobAsDead() {
        UUID id = store.enqueue("t", "{}", Duration.ZERO, 2);

        var first = store.claim(Set.of("t"), 1, LEASE).getFirst();
        assertThat(store.fail(id, first.attempt(), "boom", Duration.ofHours(1))).isTrue();
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(store.claim(Set.of("t"), 1, LEASE)).as("not due for an hour").isEmpty();
        assertThat(db.sql("SELECT run_at > now() + interval '59 minutes' FROM shared.jobs WHERE id = :id")
                .param("id", id).query(Boolean.class).single()).isTrue();

        db.sql("UPDATE shared.jobs SET run_at = now() WHERE id = :id").param("id", id).update();
        var second = store.claim(Set.of("t"), 1, LEASE).getFirst();
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(store.fail(id, second.attempt(), "boom again", Duration.ofHours(1))).isTrue();
        assertThat(status(id)).isEqualTo("DEAD");
        assertThat(db.sql("SELECT last_error FROM shared.jobs WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("boom again");
    }

    @Test
    void futureJobsAndForeignTypesAreNotClaimed() {
        store.enqueue("t", "{}", Duration.ofHours(1), 5);
        UUID other = enqueue("other");
        UUID mine = enqueue("mine");

        var claimed = store.claim(Set.of("mine"), 10, LEASE);
        assertThat(claimed).extracting(JobStore.ClaimedJob::id).containsExactly(mine);
        assertThat(status(other)).isEqualTo("QUEUED");
        assertThat(store.claim(Set.of(), 10, LEASE)).isEmpty();
    }

    @Test
    void purgeRemovesOnlyOldDoneJobs() {
        UUID oldDone = enqueue("t");
        UUID recentDone = enqueue("t");
        UUID oldDead = store.enqueue("t", "{}", Duration.ZERO, 1);
        for (var job : store.claim(Set.of("t"), 10, LEASE)) {
            if (job.id().equals(oldDead)) {
                store.fail(job.id(), job.attempt(), "x", Duration.ZERO);
            } else {
                store.complete(job.id(), job.attempt());
            }
        }
        db.sql("UPDATE shared.jobs SET finished_at = now() - interval '10 days' WHERE id IN (:a, :b)")
                .param("a", oldDone).param("b", oldDead).update();

        assertThat(store.purgeDone(Duration.ofDays(7))).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM shared.jobs WHERE id = :id").param("id", oldDone)
                .query(Long.class).single()).isZero();
        assertThat(status(recentDone)).isEqualTo("DONE");
        assertThat(status(oldDead)).as("DEAD jobs are kept for an operator").isEqualTo("DEAD");
    }
}
