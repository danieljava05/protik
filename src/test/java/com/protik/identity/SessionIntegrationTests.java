package com.protik.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.identity.persistence.SessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0012: rotation, reuse detection, expiry, logout, and the races between them. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SessionIntegrationTests {

    private static final String PASSWORD = "S3cret-Pa55word!";

    record Pair(String access, String refresh) {}

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    JwtDecoder jwtDecoder;
    @MockitoSpyBean
    SessionRepository sessionRepository;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM identity.accounts").update(); // cascades to sessions and tokens
    }

    // ---- helpers ----

    private void register(String username) throws Exception {
        mvc.perform(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(
                "{\"username\":\"%s\",\"email\":\"%s@example.com\",\"password\":\"%s\"}"
                        .formatted(username, username, PASSWORD))).andExpect(status().isCreated());
    }

    private Pair login(String username) throws Exception {
        String body = mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Pair(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private Pair newSession(String username) throws Exception {
        register(username);
        return login(username);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private Pair rotate(String refreshToken) throws Exception {
        String body = refresh(refreshToken).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Pair(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private static String sha256Hex(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private UUID familyOf(String refreshToken) throws Exception {
        return db.sql("SELECT family_id FROM identity.refresh_tokens WHERE encode(token_hash, 'hex') = :h")
                .param("h", sha256Hex(refreshToken)).query(UUID.class).single();
    }

    private String revokedReason(UUID family) {
        return db.sql("SELECT revoked_reason FROM identity.session_families WHERE id = :id").param("id", family)
                .query(String.class).optional().orElse(null);
    }

    private void ageRotation(String refreshToken, String interval) throws Exception {
        db.sql("UPDATE identity.refresh_tokens SET rotated_at = now() - CAST(:i AS interval) "
                + "WHERE encode(token_hash, 'hex') = :h").param("i", interval).param("h", sha256Hex(refreshToken)).update();
    }

    /** Makes matching tokens expired. The schema forbids expiry before issue, so issue time moves back too. */
    private void expire(String whereClause) {
        db.sql("UPDATE identity.refresh_tokens SET issued_at = now() - interval '2 days', "
                + "expires_at = now() - interval '1 second' WHERE " + whereClause).update();
    }

    private static String withoutCorrelationId(String json) {
        return json.replaceAll("\"correlationId\":\"[^\"]*\"", "");
    }

    // ---- issuing ----

    @Test
    void loginReturnsARefreshTokenAndStoresOnlyItsHash() throws Exception {
        Pair pair = newSession("hash_user");
        assertThat(pair.refresh()).startsWith("ptk_rt_").hasSizeGreaterThan(40);

        assertThat(db.sql("SELECT count(*) FROM identity.refresh_tokens WHERE encode(token_hash, 'hex') = :h")
                .param("h", sha256Hex(pair.refresh())).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM identity.refresh_tokens WHERE encode(token_hash,'escape') LIKE '%' || :t || '%'")
                .param("t", pair.refresh().substring(7)).query(Long.class).single())
                .as("the token itself is nowhere in the database").isZero();
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"hash_user\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.refreshExpiresIn").value(30L * 24 * 3600));
    }

    @Test
    void theAccessTokenNamesItsSession() throws Exception {
        Pair pair = newSession("sid_user");
        assertThat(jwtDecoder.decode(pair.access()).getClaimAsString("sid"))
                .isEqualTo(familyOf(pair.refresh()).toString());
    }

    @Test
    void everyLoginIsItsOwnSession() throws Exception {
        register("two_sessions");
        Pair a = login("two_sessions");
        Pair b = login("two_sessions");
        assertThat(a.refresh()).isNotEqualTo(b.refresh());
        assertThat(familyOf(a.refresh())).isNotEqualTo(familyOf(b.refresh()));
    }

    // ---- rotation ----

    @Test
    void refreshingRotatesTheTokenAndTheNewPairWorks() throws Exception {
        Pair first = newSession("rotate_user");
        Pair second = rotate(first.refresh());

        assertThat(second.refresh()).isNotEqualTo(first.refresh());
        assertThat(second.access()).isNotEqualTo(first.access());
        assertThat(familyOf(second.refresh())).as("same session").isEqualTo(familyOf(first.refresh()));
        assertThat(db.sql("SELECT rotated_at IS NOT NULL FROM identity.refresh_tokens WHERE encode(token_hash,'hex') = :h")
                .param("h", sha256Hex(first.refresh())).query(Boolean.class).single()).isTrue();
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + second.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("rotate_user"));
        refresh(second.refresh()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void aSessionAlwaysHasExactlyOneLiveToken() throws Exception {
        Pair pair = newSession("one_live");
        for (int i = 0; i < 5; i++) {
            pair = rotate(pair.refresh());
        }
        UUID family = familyOf(pair.refresh());
        assertThat(db.sql("SELECT count(*) FROM identity.refresh_tokens WHERE family_id = :f AND rotated_at IS NULL")
                .param("f", family).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM identity.refresh_tokens WHERE family_id = :f")
                .param("f", family).query(Long.class).single()).isEqualTo(6);
    }

    // ---- reuse detection ----

    @Test
    void reusingATokenInsideTheGraceWindowFailsButKeepsTheSession() throws Exception {
        Pair first = newSession("grace_user");
        Pair second = rotate(first.refresh());

        refresh(first.refresh()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:invalid-refresh-token"));
        assertThat(revokedReason(familyOf(second.refresh()))).as("duplicate request is not an attack").isNull();
        refresh(second.refresh()).andExpect(status().isOk());
    }

    @Test
    void reusingATokenAfterTheGraceWindowRevokesTheWholeSession() throws Exception {
        Pair first = newSession("theft_user");
        Pair second = rotate(first.refresh());
        Pair third = rotate(second.refresh());
        ageRotation(first.refresh(), "1 minute"); // far outside the 10 s grace

        // an attacker (or the real user) presents the old token...
        refresh(first.refresh()).andExpect(status().isUnauthorized());

        // ...and the thief's stolen token and the user's current token both die
        assertThat(revokedReason(familyOf(third.refresh()))).isEqualTo("TOKEN_REUSE");
        refresh(third.refresh()).andExpect(status().isUnauthorized());
        refresh(second.refresh()).andExpect(status().isUnauthorized());
    }

    @Test
    void revokingOneSessionLeavesTheUsersOtherSessionsAlone() throws Exception {
        register("multi_user");
        Pair phone = login("multi_user");
        Pair laptop = login("multi_user");
        Pair phone2 = rotate(phone.refresh());
        ageRotation(phone.refresh(), "1 minute");
        refresh(phone.refresh()).andExpect(status().isUnauthorized());

        assertThat(revokedReason(familyOf(phone2.refresh()))).isEqualTo("TOKEN_REUSE");
        assertThat(revokedReason(familyOf(laptop.refresh()))).isNull();
        refresh(laptop.refresh()).andExpect(status().isOk());
    }

    // ---- refusing bad tokens ----

    @Test
    void everyKindOfBadTokenGetsTheSameAnswer() throws Exception {
        Pair live = newSession("same_answer");
        Pair expired = rotate(live.refresh()); // we will expire this one
        expire("encode(token_hash,'hex') = '" + sha256Hex(expired.refresh()) + "'");

        String unknown = refresh("ptk_rt_" + "A".repeat(43)).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String malformed = refresh("not-a-refresh-token").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String tooLong = refresh("ptk_rt_" + "A".repeat(120)).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String expiredBody = refresh(expired.refresh()).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String rotated = refresh(live.refresh()).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(List.of(malformed, tooLong, expiredBody, rotated).stream().map(SessionIntegrationTests::withoutCorrelationId))
                .allMatch(b -> b.equals(withoutCorrelationId(unknown)));
    }

    @Test
    void blankOrMissingRefreshTokenIsAValidationError() throws Exception {
        for (String body : List.of("{}", "{\"refreshToken\":\"\"}", "{\"refreshToken\":\"" + "x".repeat(201) + "\"}")) {
            mvc.perform(post("/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anExpiredTokenIsRefusedWithoutRevokingTheSession() throws Exception {
        Pair pair = newSession("expiry_user");
        expire("true");
        refresh(pair.refresh()).andExpect(status().isUnauthorized());
        assertThat(revokedReason(familyOf(pair.refresh()))).isNull();
    }

    @Test
    void aSessionOlderThanItsMaximumAgeCannotBeRenewed() throws Exception {
        Pair pair = newSession("old_session");
        db.sql("UPDATE identity.session_families SET created_at = now() - interval '91 days'").update();
        refresh(pair.refresh()).andExpect(status().isUnauthorized());
    }

    @Test
    void aSuspendedAccountCannotRefreshAndLosesTheSession() throws Exception {
        Pair pair = newSession("suspend_me");
        db.sql("UPDATE identity.accounts SET status = 'SUSPENDED'").update();
        refresh(pair.refresh()).andExpect(status().isUnauthorized());
        assertThat(revokedReason(familyOf(pair.refresh()))).as("revocation survived the failed request")
                .isEqualTo("ADMIN");
        db.sql("UPDATE identity.accounts SET status = 'ACTIVE'").update();
        refresh(pair.refresh()).andExpect(status().isUnauthorized());
    }

    // ---- concurrency ----

    @Test
    void eightSimultaneousRefreshesOfOneTokenYieldExactlyOneWinner() throws Exception {
        Pair pair = newSession("race_refresh");
        // Widen the race: every thread reads the token state, then waits, before it can write.
        // Without the family lock all 8 would read "not rotated yet" and all would think they won.
        org.mockito.Mockito.doAnswer(invocation -> {
            Object state = invocation.callRealMethod();
            Thread.sleep(250);
            return state;
        }).when(sessionRepository).loadState(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        int clients = 8;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return refresh(pair.refresh()).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var f : results) {
            statuses.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(statuses.stream().filter(s -> s == 200).count()).as("one winner").isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 401).count()).isEqualTo(clients - 1);
        UUID family = familyOf(pair.refresh());
        assertThat(revokedReason(family)).as("racing duplicates must not kill the session").isNull();
        assertThat(db.sql("SELECT count(*) FROM identity.refresh_tokens WHERE family_id = :f AND rotated_at IS NULL")
                .param("f", family).query(Long.class).single()).as("exactly one live token left").isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM identity.refresh_tokens WHERE family_id = :f")
                .param("f", family).query(Long.class).single()).isEqualTo(2);
    }

    // ---- logout ----

    @Test
    void logoutEndsTheSessionAndIsIdempotent() throws Exception {
        Pair pair = newSession("logout_user");
        String body = "{\"refreshToken\":\"%s\"}".formatted(pair.refresh());
        mvc.perform(post("/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        assertThat(revokedReason(familyOf(pair.refresh()))).isEqualTo("LOGOUT");
        refresh(pair.refresh()).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutNeverRevealsWhetherATokenWasValid() throws Exception {
        for (String body : List.of("{}", "{\"refreshToken\":\"ptk_rt_unknownunknownunknown\"}",
                "{\"refreshToken\":\"garbage\"}")) {
            mvc.perform(post("/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void logoutAllEndsEverySessionOfTheCaller() throws Exception {
        register("logout_all");
        Pair phone = login("logout_all");
        Pair laptop = login("logout_all");
        newSession("bystander");
        Pair other = login("bystander");

        mvc.perform(post("/v1/auth/logout-all").header("Authorization", "Bearer " + phone.access()))
                .andExpect(status().isNoContent());

        refresh(phone.refresh()).andExpect(status().isUnauthorized());
        refresh(laptop.refresh()).andExpect(status().isUnauthorized());
        refresh(other.refresh()).andExpect(status().isOk()); // another user's session is untouched
    }

    @Test
    void logoutAllNeedsAnAccessToken() throws Exception {
        mvc.perform(post("/v1/auth/logout-all")).andExpect(status().isUnauthorized());
    }

    // ---- hygiene ----

    @Test
    void refreshTokensNeverAppearInLogs(CapturedOutput output) throws Exception {
        Pair first = newSession("log_tokens");
        Pair second = rotate(first.refresh());
        ageRotation(first.refresh(), "1 minute");
        refresh(first.refresh()).andExpect(status().isUnauthorized()); // logs the reuse
        mvc.perform(post("/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(second.refresh())));
        assertThat(output.getAll())
                .doesNotContain(first.refresh().substring(7))
                .doesNotContain(second.refresh().substring(7))
                .contains("Refresh token reuse detected");
    }

    @Test
    void thePurgeDeletesOnlyOldFinishedSessions() throws Exception {
        register("purge_user");
        Pair oldRevoked = login("purge_user");
        Pair recentRevoked = login("purge_user");
        Pair active = login("purge_user");
        Pair ancient = login("purge_user");
        UUID oldFamily = familyOf(oldRevoked.refresh());
        UUID recentFamily = familyOf(recentRevoked.refresh());
        UUID ancientFamily = familyOf(ancient.refresh());
        sessionRepository.revokeFamily(oldFamily, "LOGOUT");
        sessionRepository.revokeFamily(recentFamily, "LOGOUT");
        db.sql("UPDATE identity.session_families SET revoked_at = now() - interval '40 days' WHERE id = :id")
                .param("id", oldFamily).update();
        db.sql("UPDATE identity.session_families SET created_at = now() - interval '200 days' WHERE id = :id")
                .param("id", ancientFamily).update();

        int removed = sessionRepository.purge(Duration.ofDays(30), Duration.ofDays(90));

        assertThat(removed).isEqualTo(2);
        assertThat(db.sql("SELECT count(*) FROM identity.session_families").query(Long.class).single()).isEqualTo(2);
        assertThat(db.sql("SELECT count(*) FROM identity.refresh_tokens WHERE family_id IN (:a, :b)")
                .param("a", oldFamily).param("b", ancientFamily).query(Long.class).single())
                .as("tokens of purged sessions are gone too").isZero();
        refresh(active.refresh()).andExpect(status().isOk());
    }
}
