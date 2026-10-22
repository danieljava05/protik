package com.protik.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protik.TestcontainersConfiguration;
import com.protik.identity.service.TokenService;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0016: follows are idempotent, validated, announced exactly once, and reach the feed. */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(TestcontainersConfiguration.class)
class FollowIntegrationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    ApplicationEvents events;

    private final UUID me = UUID.randomUUID();

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM social.follows").update();
        db.sql("DELETE FROM feed.following").update();
        db.sql("DELETE FROM identity.accounts WHERE username LIKE 'tf_%'").update();
    }

    // ---- helpers ----

    private UUID account(String status) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO identity.accounts (id, username, status) VALUES (:id, :name, :status)")
                .param("id", id).param("name", "tf_" + id.toString().replace("-", "").substring(0, 12)).param("status", status).update();
        return id;
    }

    private UUID account() {
        return account("ACTIVE");
    }

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private ResultActions follow(UUID target) throws Exception {
        return mvc.perform(put("/v1/users/" + target + "/follow").header("Authorization", bearer(me)));
    }

    private ResultActions unfollow(UUID target) throws Exception {
        return mvc.perform(delete("/v1/users/" + target + "/follow").header("Authorization", bearer(me)));
    }

    private ResultActions state(UUID target) throws Exception {
        return mvc.perform(get("/v1/users/" + target + "/follow").header("Authorization", bearer(me)));
    }

    private long followRows() {
        return db.sql("SELECT count(*) FROM social.follows").query(Long.class).single();
    }

    private long followed(UUID target) {
        return events.stream(UserFollowed.class).filter(e -> e.creatorId().equals(target)).count();
    }

    // ---- behaviour ----

    @Test
    void followingCreatesTheRelationshipAndTellsTheWorldOnce() throws Exception {
        UUID target = account();
        follow(target).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        state(target).andExpect(jsonPath("$.following").value(true));
        assertThat(followRows()).isEqualTo(1);
        assertThat(followed(target)).isEqualTo(1);
    }

    @Test
    void followingTwiceChangesNothingAndAnnouncesNothingTheSecondTime() throws Exception {
        UUID target = account();
        follow(target).andExpect(status().isNoContent());
        follow(target).andExpect(status().isNoContent());
        follow(target).andExpect(status().isNoContent());
        assertThat(followRows()).isEqualTo(1);
        assertThat(followed(target)).as("one real change, one event").isEqualTo(1);
    }

    @Test
    void unfollowingRemovesItAnnouncesOnceAndIsRepeatable() throws Exception {
        UUID target = account();
        follow(target);
        unfollow(target).andExpect(status().isNoContent());
        unfollow(target).andExpect(status().isNoContent());
        state(target).andExpect(jsonPath("$.following").value(false));
        assertThat(followRows()).isZero();
        assertThat(events.stream(UserUnfollowed.class).filter(e -> e.creatorId().equals(target)).count()).isEqualTo(1);
    }

    @Test
    void unfollowingSomeoneNeverFollowedIsASilentNoOp() throws Exception {
        unfollow(account()).andExpect(status().isNoContent());
        unfollow(UUID.randomUUID()).andExpect(status().isNoContent());
        assertThat(events.stream(UserUnfollowed.class).count()).isZero();
    }

    @Test
    void youCannotFollowYourself() throws Exception {
        follow(me).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:validation-failed"));
        assertThat(followRows()).isZero();
    }

    @Test
    void youCannotFollowSomeoneWhoDoesNotExistOrIsNotActive() throws Exception {
        follow(UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:not-found"));
        follow(account("SUSPENDED")).andExpect(status().isNotFound());
        follow(account("DELETION_PENDING")).andExpect(status().isNotFound());
        assertThat(followRows()).isZero();
        assertThat(events.stream(UserFollowed.class).count()).isZero();
    }

    @Test
    void aMalformedUserIdIsABadRequest() throws Exception {
        mvc.perform(put("/v1/users/not-a-uuid/follow").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        UUID target = account();
        mvc.perform(put("/v1/users/" + target + "/follow")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/users/" + target + "/follow")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/users/" + target + "/follow")).andExpect(status().isUnauthorized());
    }

    @Test
    void relationshipsBelongToTheCaller() throws Exception {
        UUID target = account();
        UUID other = UUID.randomUUID();
        follow(target);
        mvc.perform(get("/v1/users/" + target + "/follow").header("Authorization", bearer(other)))
                .andExpect(jsonPath("$.following").value(false));
    }

    @Test
    void tenSimultaneousFollowsMakeOneRelationshipAndOneEvent() throws Exception {
        UUID target = account();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return follow(target).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (var f : results) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(204);
        }
        pool.shutdownNow();
        assertThat(followRows()).isEqualTo(1);
        assertThat(followed(target)).as("racing duplicates announce one change").isEqualTo(1);
    }

    @Test
    void theFeedLearnsAboutFollowsAndUnfollows() throws Exception {
        UUID target = account();
        follow(target);
        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT count(*) FROM feed.following WHERE user_id = :u AND creator_id = :c")
                        .param("u", me).param("c", target).query(Long.class).single() == 1);
        unfollow(target);
        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT count(*) FROM feed.following WHERE user_id = :u").param("u", me).query(Long.class).single() == 0);
    }
}
