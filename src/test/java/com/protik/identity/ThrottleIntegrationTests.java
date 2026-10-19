package com.protik.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protik.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0013 applied to login and registration. Limits are small so the tests stay fast. */
@SpringBootTest(properties = {
        "protik.throttle.login-pair-limit=5",
        "protik.throttle.login-account-limit=8",
        "protik.throttle.login-ip-limit=12",
        "protik.throttle.login-window=15m",
        "protik.throttle.register-ip-limit=3",
        "protik.throttle.register-window=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ThrottleIntegrationTests {

    private static final String PASSWORD = "S3cret-Pa55word!";
    private static final String WRONG = "Wrong-Pa55word!!";
    private static final AtomicInteger NEXT_REGISTRATION_ADDRESS = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @MockitoSpyBean
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM identity.accounts").update();
        db.sql("DELETE FROM shared.rate_limits").update();
    }

    // ---- helpers ----

    /** A unique address per call so setup registrations never trip the registration limit. */
    private static String freshAddress() {
        return "10.250." + (NEXT_REGISTRATION_ADDRESS.get() / 250) + "." + (NEXT_REGISTRATION_ADDRESS.getAndIncrement() % 250);
    }

    private void createAccount(String username) throws Exception {
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s@example.com\",\"password\":\"%s\"}"
                                .formatted(username, username, PASSWORD))
                        .with(from(freshAddress())))
                .andExpect(status().isCreated());
        db.sql("DELETE FROM shared.rate_limits").update(); // setup must not count against the test
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private ResultActions login(String identifier, String password, String address) throws Exception {
        return mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"%s\",\"password\":\"%s\"}".formatted(identifier, password))
                .with(from(address)));
    }

    private int loginStatus(String identifier, String password, String address) throws Exception {
        return login(identifier, password, address).andReturn().getResponse().getStatus();
    }

    // ---- per account and address ----

    @Test
    void afterTheLimitEvenTheCorrectPasswordIsRefusedAndNeverChecked() throws Exception {
        createAccount("victim");
        for (int i = 0; i < 5; i++) {
            login("victim", WRONG, "198.51.100.1").andExpect(status().isUnauthorized());
        }
        clearInvocations(passwordEncoder);

        login("victim", PASSWORD, "198.51.100.1").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:too-many-attempts"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("Retry-After"));
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void retryAfterIsWithinTheWindow() throws Exception {
        createAccount("waiter");
        for (int i = 0; i < 5; i++) {
            login("waiter", WRONG, "198.51.100.2");
        }
        String retryAfter = login("waiter", WRONG, "198.51.100.2").andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 900L);
    }

    @Test
    void oneAttackerDoesNotLockTheVictimOutOfTheirOwnDevices() throws Exception {
        createAccount("victim2");
        for (int i = 0; i < 6; i++) {
            login("victim2", WRONG, "198.51.100.3");
        }
        assertThat(loginStatus("victim2", WRONG, "198.51.100.3")).isEqualTo(429);

        login("victim2", PASSWORD, "203.0.113.50").andExpect(status().isOk()); // the real user, elsewhere
    }

    @Test
    void aSuccessfulLoginClearsThePairButEarlierFailuresStayOnTheSharedCounters() throws Exception {
        createAccount("forgetful");
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 3; i++) {
                login("forgetful", WRONG, "198.51.100.4").andExpect(status().isUnauthorized());
            }
            // Without the reset the pair would be at 3 + 1 + 3 + 1 = 8 > 5 and the second round's
            // successful login would be refused.
            login("forgetful", PASSWORD, "198.51.100.4").andExpect(status().isOk());
        }
        // Only the failed attempts remain on the account and address counters: the successful
        // attempts were handed back, and the pair counter was forgotten.
        List<Integer> counts = db.sql("SELECT count FROM shared.rate_limits ORDER BY key").query(Integer.class).list();
        assertThat(counts).containsExactly(6, 6);
    }

    // ---- per account, any address ----

    @Test
    void aDistributedAttackOnOneAccountIsStoppedByTheAccountLimit() throws Exception {
        createAccount("target");
        for (int i = 0; i < 8; i++) {
            login("target", WRONG, "192.0.2." + (10 + i)).andExpect(status().isUnauthorized()); // one failure per address
        }
        login("target", PASSWORD, "192.0.2.99").andExpect(status().isTooManyRequests());
    }

    // ---- per address, any account ----

    @Test
    void credentialStuffingFromOneAddressIsStoppedByTheAddressLimit() throws Exception {
        for (int i = 0; i < 12; i++) {
            login("someone" + i, WRONG, "198.51.100.5").andExpect(status().isUnauthorized());
        }
        login("a_new_name", WRONG, "198.51.100.5").andExpect(status().isTooManyRequests());
        login("someone_else", WRONG, "198.51.100.6").andExpect(status().isUnauthorized()); // other addresses unaffected
    }

    // ---- no information leaks ----

    @Test
    void unknownAccountsAreThrottledExactlyLikeRealOnes() throws Exception {
        createAccount("real_one");
        List<Integer> real = new ArrayList<>();
        List<Integer> ghost = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            real.add(loginStatus("real_one", WRONG, "198.51.100.7"));
            ghost.add(loginStatus("ghost_one", WRONG, "198.51.100.8"));
        }
        assertThat(ghost).isEqualTo(real).containsExactly(401, 401, 401, 401, 401, 429, 429);
    }

    @Test
    void aForgedForwardedForHeaderCannotEvadeTheLimit() throws Exception {
        createAccount("spoofed");
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"identifier\":\"spoofed\",\"password\":\"%s\"}".formatted(WRONG))
                    .header("X-Forwarded-For", "1.2.3." + i).with(from("198.51.100.9")));
        }
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"spoofed\",\"password\":\"%s\"}".formatted(PASSWORD))
                        .header("X-Forwarded-For", "9.9.9.9").with(from("198.51.100.9")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void counterKeysContainNeitherIdentifiersNorAddresses() throws Exception {
        createAccount("private.person");
        login("private.person@example.com", WRONG, "198.51.100.10");
        List<String> keys = db.sql("SELECT key FROM shared.rate_limits").query(String.class).list();
        assertThat(keys).isNotEmpty().allMatch(k -> k.matches("[0-9a-f]{64}"));
        assertThat(String.join(",", keys)).doesNotContain("198").doesNotContain("private");
    }

    // ---- windows and bursts ----

    @Test
    void theBlockEndsWhenTheWindowEnds() throws Exception {
        createAccount("patient");
        for (int i = 0; i < 6; i++) {
            login("patient", WRONG, "198.51.100.11");
        }
        login("patient", PASSWORD, "198.51.100.11").andExpect(status().isTooManyRequests());
        db.sql("UPDATE shared.rate_limits SET window_start = now() - interval '16 minutes'").update();
        login("patient", PASSWORD, "198.51.100.11").andExpect(status().isOk());
    }

    @Test
    void thirtySimultaneousGuessesLetExactlyTheLimitReachThePasswordCheck() throws Exception {
        createAccount("hammered");
        clearInvocations(passwordEncoder);
        int attackers = 30;
        ExecutorService pool = Executors.newFixedThreadPool(attackers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < attackers; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return loginStatus("hammered", WRONG, "198.51.100.12");
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var f : results) {
            statuses.add(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(attackers - 5);
        long verifications = mockingDetails(passwordEncoder).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("matches")).count();
        assertThat(verifications).as("only 5 of 30 simultaneous guesses cost an Argon2 verification")
                .isEqualTo(5);
    }

    // ---- registration ----

    @Test
    void registrationsFromOneAddressAreLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"spam%d\",\"email\":\"spam%d@example.com\",\"password\":\"%s\"}"
                                    .formatted(i, i, PASSWORD))
                            .with(from("198.51.100.20")))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"spam3\",\"email\":\"spam3@example.com\",\"password\":\"%s\"}"
                                .formatted(PASSWORD))
                        .with(from("198.51.100.20")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"spam4\",\"email\":\"spam4@example.com\",\"password\":\"%s\"}"
                                .formatted(PASSWORD))
                        .with(from("198.51.100.21")))
                .andExpect(status().isCreated());
        assertThat(db.sql("SELECT count(*) FROM identity.accounts WHERE username = 'spam3'").query(Long.class).single())
                .as("a blocked registration creates nothing").isZero();
    }
}
