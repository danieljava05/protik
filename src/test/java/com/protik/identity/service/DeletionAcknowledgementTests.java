package com.protik.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protik.TestcontainersConfiguration;
import com.protik.identity.DeletionAcknowledgements;
import com.protik.identity.UserDeletionRequested;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR 0020: the account goes only when every module has acknowledged, whatever order and however concurrently. */
@SpringBootTest
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class DeletionAcknowledgementTests {

    static final List<String> MODULES = List.of("video", "feed", "social", "engagement", "moderation");

    @Autowired
    DeletionService deletion;
    @Autowired
    DeletionAcknowledgements acknowledgements;
    @Autowired
    JdbcClient db;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    ApplicationEvents events;

    private UUID account;
    private UUID request;

    @BeforeEach
    void setUp() {
        account = UUID.randomUUID();
        request = UUID.randomUUID();
        db.sql("INSERT INTO identity.accounts (id, username, status) VALUES (:id, :u, 'DELETION_PENDING')")
                .param("id", account).param("u", "ack_" + account.toString().substring(0, 8)).update();
        db.sql("INSERT INTO identity.deletion_requests (id, account_id) VALUES (:r, :a)")
                .param("r", request).param("a", account).update();
    }

    private void ack(String module) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> acknowledgements.acknowledge(request, module));
    }

    private boolean accountExists() {
        return db.sql("SELECT EXISTS (SELECT 1 FROM identity.accounts WHERE id = :id)").param("id", account)
                .query(Boolean.class).single();
    }

    private boolean completed() {
        return db.sql("SELECT completed_at IS NOT NULL FROM identity.deletion_requests WHERE id = :r").param("r", request)
                .query(Boolean.class).single();
    }

    @Test
    void theAccountSurvivesUntilTheLastModuleAcknowledges() {
        for (String module : MODULES.subList(0, 4)) {
            ack(module);
            assertThat(accountExists()).as("after " + module).isTrue();
        }
        assertThat(completed()).isFalse();
        ack("moderation");
        assertThat(accountExists()).isFalse();
        assertThat(completed()).isTrue();
    }

    @Test
    void acknowledgingTwiceDoesNotCountTwice() {
        for (int i = 0; i < 6; i++) {
            ack("video");
        }
        assertThat(accountExists()).isTrue();
        assertThat(completed()).isFalse();
    }

    @Test
    void anAcknowledgementAfterCompletionIsHarmless() {
        MODULES.forEach(this::ack);
        ack("feed");
        assertThat(completed()).isTrue();
    }

    @Test
    void anUnknownModuleIsRejected() {
        assertThatThrownBy(() -> ack("billing")).isInstanceOf(IllegalArgumentException.class);
        assertThat(accountExists()).isTrue();
    }

    @Test
    void anAcknowledgementOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> acknowledgements.acknowledge(request, "video"))
                .hasMessageContaining("transaction");
    }

    @Test
    void fiveModulesAcknowledgingAtTheSameTimeStillDeleteTheAccountExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (String module : MODULES) {
                futures.add(pool.submit(() -> {
                    go.await();
                    ack(module);
                    return null;
                }));
            }
            go.countDown();
            for (Future<Object> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(accountExists()).isFalse();
        assertThat(completed()).isTrue();
    }

    @Test
    void anOverdueRequestIsAnnouncedAgainButARecentOneIsNot() {
        deletion.retryOverdue();
        assertThat(events.stream(UserDeletionRequested.class).filter(e -> e.requestId().equals(request))).isEmpty();

        db.sql("UPDATE identity.deletion_requests SET requested_at = now() - interval '1 hour' WHERE id = :r")
                .param("r", request).update();
        deletion.retryOverdue();
        assertThat(events.stream(UserDeletionRequested.class).filter(e -> e.requestId().equals(request))).hasSize(1);
    }
}
