package com.protik.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protik.TestcontainersConfiguration;
import com.protik.shared.TestEndpointsAccess;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0009: retried POSTs are safe, including under concurrency and after failures. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdempotencyTests {

    record Thing(String name) {}

    private static final String ENDPOINT = "POST /v1/things";
    private static final UUID ANON = IdempotencyService.ANONYMOUS_SCOPE;

    @Autowired
    MockMvc mvc;
    @Autowired
    IdempotencyService idempotency;
    @Autowired
    IdempotencyStore store;
    @Autowired
    JdbcClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM shared.idempotency_keys").update();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private ResultActions create(String key, String name) throws Exception {
        var request = post("/v1/_test/create").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private IdempotentResponse<Thing> run(UUID scope, String key, String endpoint, AtomicInteger runs, int status) {
        return idempotency.execute(scope, key, endpoint, new Thing("a"), Thing.class, () -> {
            runs.incrementAndGet();
            return new IdempotentAction.Outcome<>(status, new Thing("a"));
        });
    }

    // ---- over HTTP ----

    @Test
    void aRetryReplaysTheOriginalResponseWithoutRunningTheActionAgain() throws Exception {
        String key = key();
        create(key, "ada").andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.sequence").exists());
        int before = TestEndpointsAccess.created();

        create(key, "ada").andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.name").value("ada"));
        assertThat(TestEndpointsAccess.created()).as("action did not run again").isEqualTo(before);
    }

    @Test
    void theReplayCarriesTheSameBodyAsTheOriginal() throws Exception {
        String key = key();
        String first = create(key, "same").andReturn().getResponse().getContentAsString();
        String replay = create(key, "same").andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void requestsWithoutAKeyAlwaysRun() throws Exception {
        int before = TestEndpointsAccess.created();
        create(null, "a").andExpect(status().isCreated());
        create(null, "a").andExpect(status().isCreated());
        assertThat(TestEndpointsAccess.created()).isEqualTo(before + 2);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRefused() throws Exception {
        String key = key();
        create(key, "first").andExpect(status().isCreated());
        create(key, "second").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:idempotency-key-reused"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void malformedKeysAreRejected() throws Exception {
        create("has space", "x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:validation-failed"));
        create("k".repeat(256), "x").andExpect(status().isBadRequest());
        create("", "x").andExpect(status().isBadRequest());
    }

    // ---- service level ----

    @Test
    void theSameKeyWithADifferentEndpointIsRefused() {
        String key = key();
        run(ANON, key, ENDPOINT, new AtomicInteger(), 201);
        assertThatThrownBy(() -> run(ANON, key, "POST /v1/other", new AtomicInteger(), 201))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.problemType()).isEqualTo(ProblemType.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void aFailedActionStoresNothingSoTheRetryRunsAgain() {
        String key = key();
        var runs = new AtomicInteger();
        assertThatThrownBy(() -> idempotency.execute(ANON, key, ENDPOINT, new Thing("a"), Thing.class, () -> {
            runs.incrementAndGet();
            throw new ApiException(ProblemType.CONFLICT, "username taken");
        })).isInstanceOf(ApiException.class);
        assertThat(count()).as("claim rolled back with the failed action").isZero();

        var retry = run(ANON, key, ENDPOINT, runs, 201);
        assertThat(retry.replayed()).isFalse();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void twoUsersMayUseTheSameKey() {
        String key = key();
        var runs = new AtomicInteger();
        for (UUID user : List.of(UUID.randomUUID(), UUID.randomUUID())) {
            assertThat(run(user, key, ENDPOINT, runs, 201).replayed()).isFalse();
        }
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void anExpiredKeyIsTreatedAsNew() {
        String key = key();
        var runs = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            run(ANON, key, ENDPOINT, runs, 201);
            db.sql("UPDATE shared.idempotency_keys SET expires_at = now() - interval '1 second'").update();
        }
        assertThat(runs.get()).as("expired key ran the action again").isEqualTo(2);
    }

    @Test
    void anEmptyResponseBodyIsReplayedToo() {
        String key = key();
        var runs = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            var r = idempotency.execute(ANON, key, ENDPOINT, new Thing("a"), Thing.class, () -> {
                runs.incrementAndGet();
                return new IdempotentAction.Outcome<>(204, null);
            });
            assertThat(r.status()).isEqualTo(204);
            assertThat(r.body()).isNull();
        }
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void theStoredFingerprintIsKeyedNotAPlainHash() throws Exception {
        String key = key();
        Thing secret = new Thing("hunter2");
        idempotency.execute(ANON, key, ENDPOINT, secret, Thing.class,
                () -> new IdempotentAction.Outcome<>(201, secret));
        byte[] stored = db.sql("SELECT request_hash FROM shared.idempotency_keys WHERE key = :k").param("k", key)
                .query(byte[].class).single();
        byte[] plain = MessageDigest.getInstance("SHA-256")
                .digest((ENDPOINT + "\n{\"name\":\"hunter2\"}").getBytes());
        // an attacker holding the database cannot confirm password guesses against an unkeyed hash
        assertThat(stored).hasSize(32).isNotEqualTo(plain);
    }

    @Test
    void purgeRemovesOnlyExpiredKeys() {
        run(ANON, key(), ENDPOINT, new AtomicInteger(), 201);
        String oldKey = key();
        run(ANON, oldKey, ENDPOINT, new AtomicInteger(), 201);
        db.sql("UPDATE shared.idempotency_keys SET expires_at = now() - interval '1 hour' WHERE key = :k")
                .param("k", oldKey).update();
        assertThat(store.purgeExpired()).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesRunTheActionExactlyOnce() throws Exception {
        String key = key();
        int clients = 12;
        var runs = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<IdempotentResponse<Thing>>> futures = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return idempotency.execute(ANON, key, ENDPOINT, new Thing("a"), Thing.class, () -> {
                    runs.incrementAndGet();
                    sleep(400); // long enough that every duplicate overlaps the first
                    return new IdempotentAction.Outcome<>(201, new Thing("a"));
                });
            }));
        }
        go.countDown();
        List<IdempotentResponse<Thing>> results = new ArrayList<>();
        for (var f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(runs.get()).as("the action ran once despite 12 simultaneous requests").isEqualTo(1);
        assertThat(results).allMatch(r -> r.status() == 201 && r.body().equals(new Thing("a")));
        assertThat(results.stream().filter(r -> !r.replayed()).count()).as("one original, the rest replays")
                .isEqualTo(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aDuplicateWaitingOnAFailingFirstRunsForRealInstead() throws Exception {
        String key = key();
        var attempts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Object> first = pool.submit(() -> {
            try {
                return idempotency.execute(ANON, key, ENDPOINT, new Thing("a"), Thing.class, () -> {
                    attempts.incrementAndGet();
                    sleep(500); // the duplicate arrives while this is still running
                    throw new ApiException(ProblemType.CONFLICT, "first fails");
                });
            } catch (ApiException e) {
                return e;
            }
        });
        Thread.sleep(150);
        Future<IdempotentResponse<Thing>> second = pool.submit(() -> run(ANON, key, ENDPOINT, attempts, 201));

        assertThat(first.get(30, TimeUnit.SECONDS)).isInstanceOf(ApiException.class);
        var result = second.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(result.replayed()).as("the failure was not stored, so this one ran for real").isFalse();
        assertThat(attempts.get()).isEqualTo(2);
    }

    private long count() {
        return db.sql("SELECT count(*) FROM shared.idempotency_keys").query(Long.class).single();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
