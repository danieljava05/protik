package com.protik.shared.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.protik.TestcontainersConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The whole loop with real worker threads: claim, run, retry, recover, fence. */
@SpringBootTest(properties = {
        "protik.jobs.enabled=true",
        "protik.jobs.worker-threads=4",
        "protik.jobs.poll-interval=20ms",
        "protik.jobs.lease=1s",
        "protik.jobs.retry-base=50ms",
        "protik.jobs.retry-max=200ms",
        "protik.jobs.maintenance-interval=200ms"})
@Import({TestcontainersConfiguration.class, JobWorkerTests.Handlers.class})
// Close the context (and stop its worker threads) right after this class, while its
// database container still exists; otherwise the threads outlive the container at JVM exit.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class JobWorkerTests {

    static final Map<UUID, AtomicInteger> RUNS = new ConcurrentHashMap<>();
    static final Map<UUID, AtomicInteger> LOST_LEASE = new ConcurrentHashMap<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {

        @Bean
        JobHandler countHandler() {
            return handler("count", ctx -> RUNS.computeIfAbsent(ctx.id(), k -> new AtomicInteger()).incrementAndGet());
        }

        /** Fails on the first attempt, succeeds on the second. */
        @Bean
        JobHandler flakyHandler() {
            return handler("flaky", ctx -> {
                RUNS.computeIfAbsent(ctx.id(), k -> new AtomicInteger()).incrementAndGet();
                if (ctx.attempt() == 1) {
                    throw new IllegalStateException("transient failure");
                }
            });
        }

        @Bean
        JobHandler poisonHandler() {
            return handler("poison", ctx -> {
                throw new IllegalArgumentException("always broken");
            });
        }

        /** Runs for 2.5s, three times longer than the 1s lease, extending it as it goes. */
        @Bean
        JobHandler slowHandler() {
            return handler("slow", ctx -> {
                RUNS.computeIfAbsent(ctx.id(), k -> new AtomicInteger()).incrementAndGet();
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(500);
                    if (!ctx.extendLease(Duration.ofSeconds(1))) {
                        LOST_LEASE.computeIfAbsent(ctx.id(), k -> new AtomicInteger()).incrementAndGet();
                        return;
                    }
                }
            });
        }

        private static JobHandler handler(String type, ThrowingConsumer body) {
            return new JobHandler() {
                @Override
                public String type() {
                    return type;
                }

                @Override
                public void handle(JobContext context) throws Exception {
                    body.accept(context);
                }
            };
        }
    }

    @FunctionalInterface
    interface ThrowingConsumer {
        void accept(JobContext ctx) throws Exception;
    }

    @Autowired
    JobStore store;
    @Autowired
    JdbcClient db;

    @BeforeEach
    void clean() {
        RUNS.clear();
        LOST_LEASE.clear();
        db.sql("DELETE FROM shared.jobs").update();
    }

    private String status(UUID id) {
        return db.sql("SELECT status FROM shared.jobs WHERE id = :id").param("id", id).query(String.class).single();
    }

    private UUID enqueue(String type, int maxAttempts) {
        return store.enqueue(type, "{}", Duration.ZERO, maxAttempts);
    }

    @Test
    void manyJobsAreEachRunExactlyOnceByTheWorkerPool() {
        List<UUID> ids = java.util.stream.IntStream.range(0, 200).mapToObj(i -> enqueue("count", 3)).toList();

        await().atMost(Duration.ofSeconds(60)).until(() ->
                db.sql("SELECT count(*) FROM shared.jobs WHERE status = 'DONE'").query(Long.class).single() == 200);

        assertThat(RUNS).hasSize(200);
        assertThat(RUNS.values()).allMatch(n -> n.get() == 1);
        assertThat(db.sql("SELECT count(*) FROM shared.jobs WHERE attempts = 1").query(Long.class).single())
                .isEqualTo(200);
        assertThat(ids).allMatch(id -> status(id).equals("DONE"));
    }

    @Test
    void aFailedJobIsRetriedAndThenSucceeds() {
        UUID id = enqueue("flaky", 3);
        await().atMost(Duration.ofSeconds(20)).until(() -> status(id).equals("DONE"));
        assertThat(RUNS.get(id).get()).isEqualTo(2);
        assertThat(db.sql("SELECT attempts FROM shared.jobs WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(db.sql("SELECT last_error FROM shared.jobs WHERE id = :id").param("id", id)
                .query(String.class).optional()).as("success clears the error").isEmpty();
    }

    @Test
    void aPoisonJobGoesDeadWithoutBlockingOthers() {
        UUID poison = enqueue("poison", 3);
        UUID good = enqueue("count", 3);

        await().atMost(Duration.ofSeconds(20)).until(() -> status(poison).equals("DEAD") && status(good).equals("DONE"));
        assertThat(db.sql("SELECT attempts FROM shared.jobs WHERE id = :id").param("id", poison)
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(db.sql("SELECT last_error FROM shared.jobs WHERE id = :id").param("id", poison)
                .query(String.class).single()).contains("IllegalArgumentException").contains("always broken");
    }

    @Test
    void aJobWithNoHandlerHereIsLeftAloneForSomeoneElse() throws Exception {
        UUID orphan = enqueue("handled-by-another-deployment", 3);
        UUID good = enqueue("count", 3);
        await().atMost(Duration.ofSeconds(20)).until(() -> status(good).equals("DONE"));
        Thread.sleep(300);
        assertThat(status(orphan)).isEqualTo("QUEUED");
        assertThat(db.sql("SELECT attempts FROM shared.jobs WHERE id = :id").param("id", orphan)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aLongJobThatExtendsItsLeaseIsNotStolenByRecovery() {
        UUID id = enqueue("slow", 3);
        await().atMost(Duration.ofSeconds(30)).until(() -> status(id).equals("DONE"));
        // 2.5s of work against a 1s lease and a recovery pass every 200ms
        assertThat(RUNS.get(id).get()).as("ran once, was never recovered and re-run").isEqualTo(1);
        assertThat(LOST_LEASE).isEmpty();
        assertThat(db.sql("SELECT attempts FROM shared.jobs WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
    }
}
