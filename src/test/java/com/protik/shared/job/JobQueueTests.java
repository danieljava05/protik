package com.protik.shared.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protik.TestcontainersConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Enqueue semantics (ADR 0008 decision 7) and retry backoff. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobQueueTests {

    @Autowired
    JobQueue queue;
    @Autowired
    JdbcClient db;
    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM shared.jobs").update();
    }

    private long count() {
        return db.sql("SELECT count(*) FROM shared.jobs").query(Long.class).single();
    }

    @Test
    void enqueueOutsideATransactionIsRefusedForBothOverloads() {
        assertThatThrownBy(() -> queue.enqueue("t", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> queue.enqueue("t", "{}", Duration.ZERO, 3))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void aCommittedTransactionLeavesTheJob() {
        UUID id = new TransactionTemplate(txManager).execute(tx -> queue.enqueue("transcode", "{\"video\":\"v1\"}"));
        assertThat(db.sql("SELECT payload ->> 'video' FROM shared.jobs WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("v1");
        assertThat(db.sql("SELECT status FROM shared.jobs WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("QUEUED");
        assertThat(id.version()).as("UUIDv7 per ADR 0005").isEqualTo(7);
    }

    @Test
    void aRolledBackTransactionLeavesNoJob() {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            queue.enqueue("transcode", "{}");
            tx.setRollbackOnly(); // the business change failed: the job must vanish with it
        });
        assertThat(count()).isZero();
    }

    @Test
    void anExceptionAfterEnqueueRollsTheJobBack() {
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            queue.enqueue("transcode", "{}");
            throw new IllegalStateException("business logic failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void delayedJobsStoreADatabaseClockRunTime() {
        new TransactionTemplate(txManager).executeWithoutResult(
                tx -> queue.enqueue("t", "{}", Duration.ofMinutes(10), 3));
        assertThat(db.sql("SELECT run_at > now() + interval '9 minutes' AND max_attempts = 3 FROM shared.jobs")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void invalidInputIsRejected() {
        var tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> queue.enqueue("t", "{not json")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> queue.enqueue("", "{}")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> queue.enqueue("x".repeat(101), "{}")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> queue.enqueue("t", "{}", Duration.ZERO, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(t -> queue.enqueue("t", "{}", Duration.ofSeconds(-1), 3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count()).isZero();
    }

    @Test
    void backoffDoublesWithJitterAndIsCapped() {
        var props = new JobProperties(true, 1, Duration.ofSeconds(1), Duration.ofMinutes(5),
                Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofDays(7),
                Duration.ofSeconds(30));
        for (int i = 0; i < 200; i++) { // jitter is random: check many draws
            assertThat(JobWorker.backoff(1, props).toMillis()).isBetween(8_000L, 12_000L);   // 10s +/-20%
            assertThat(JobWorker.backoff(2, props).toMillis()).isBetween(16_000L, 24_000L);  // 20s
            assertThat(JobWorker.backoff(3, props).toMillis()).isBetween(32_000L, 48_000L);  // 40s
            assertThat(JobWorker.backoff(10, props).toMillis()).isLessThanOrEqualTo(60_000L); // capped at 1m
        }
    }

    @Test
    void workerThreadCountMustBePositive() {
        assertThatThrownBy(() -> new JobProperties(true, 0, Duration.ofSeconds(1), Duration.ofMinutes(5),
                Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(30), Duration.ofDays(7),
                Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }
}
