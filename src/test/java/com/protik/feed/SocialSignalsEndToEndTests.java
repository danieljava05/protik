package com.protik.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.engagement.service.CounterService;
import com.protik.identity.service.TokenService;
import com.protik.video.VideoPublished;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The whole chain through the public API: a person follows and likes, and the feed (which reads only its
 * own copies, fed by events) reflects it. Nothing here writes to the feed directly.
 */
@SpringBootTest(properties = {"protik.engagement.refresh-interval=PT1H", "protik.engagement.reconcile-interval=PT1H"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SocialSignalsEndToEndTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    CounterService counters;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    PlatformTransactionManager txManager;

    private final UUID viewer = UUID.randomUUID();
    private UUID creatorA;
    private UUID creatorB;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM feed.candidates").update();
        db.sql("DELETE FROM feed.following").update();
        db.sql("DELETE FROM feed.user_interests").update();
        db.sql("DELETE FROM feed.user_feed_settings").update();
        db.sql("DELETE FROM social.follows").update();
        db.sql("DELETE FROM engagement.video_likes").update();
        db.sql("DELETE FROM engagement.video_counters").update();
        db.sql("DELETE FROM engagement.dirty_counters").update();
        db.sql("DELETE FROM video.videos WHERE description = 'e2e'").update();
        db.sql("DELETE FROM identity.accounts WHERE username LIKE 'e2e_%'").update();
        creatorA = account();
        creatorB = account();
    }

    // ---- helpers ----

    private UUID account() {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO identity.accounts (id, username) VALUES (:id, :n)")
                .param("id", id).param("n", "e2e_" + id.toString().replace("-", "").substring(0, 12)).update();
        return id;
    }

    /** A real published video: a row in the video module's table plus the event the feed reacts to. */
    private UUID publish(UUID creator, Duration age, Set<String> tags) {
        UUID id = UUID.randomUUID();
        Instant at = Instant.now().minus(age);
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            db.sql("INSERT INTO video.videos (id, creator_id, description, status, published_at, duration_ms) "
                    + "VALUES (:id, :c, 'e2e', 'PUBLISHED', :at, 9000)")
                    .param("id", id).param("c", creator).param("at", at.atOffset(java.time.ZoneOffset.UTC)).update();
            events.publishEvent(new VideoPublished(id, creator, at, 9000, "en", tags));
        });
        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT count(*) FROM feed.candidates WHERE video_id = :id").param("id", id).query(Long.class).single() == 1);
        return id;
    }

    private String bearer() {
        return "Bearer " + tokens.issue(viewer, UUID.randomUUID()).value();
    }

    private void call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header("Authorization", bearer())).andExpect(status().isNoContent());
    }

    private String feed() throws Exception {
        return mvc.perform(get("/v1/feed").header("Authorization", bearer())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void useWeights(String json) throws Exception {
        mvc.perform(put("/v1/feed/preferences").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private Number component(String json, UUID video, String component) {
        List<Number> found = JsonPath.read(json,
                "$.items[?(@.videoId=='" + video + "')].explanation[?(@.component=='" + component + "')].value");
        return found.getFirst();
    }

    private void waitFor(java.util.concurrent.Callable<Boolean> condition) {
        await().atMost(Duration.ofSeconds(30)).until(condition);
    }

    // ---- the chain ----

    @Test
    void followingACreatorRaisesTheirVideosAndShowsInTheFeed() throws Exception {
        UUID fromA = publish(creatorA, Duration.ofHours(3), Set.of());
        UUID fromB = publish(creatorB, Duration.ofHours(2), Set.of());
        useWeights("{\"mode\":\"DEFAULT\",\"weights\":{\"FOLLOWING\":1,\"RECENCY\":0.1,\"ENGAGEMENT\":0,\"TOPIC\":0}}");
        assertThat(JsonPath.<List<String>>read(feed(), "$.items[*].videoId")).first().isEqualTo(fromB.toString());

        call(put("/v1/users/" + creatorA + "/follow"));
        waitFor(() -> db.sql("SELECT count(*) FROM feed.following WHERE user_id = :u").param("u", viewer).query(Long.class).single() == 1);

        String json = feed();
        assertThat(JsonPath.<List<String>>read(json, "$.items[*].videoId")).first().isEqualTo(fromA.toString());
        assertThat(component(json, fromA, "FOLLOWING").doubleValue()).isEqualTo(1.0);
        assertThat(component(json, fromB, "FOLLOWING").doubleValue()).isZero();
        assertThat(JsonPath.<List<Boolean>>read(json, "$.items[?(@.videoId=='" + fromA + "')].followingCreator").getFirst()).isTrue();
    }

    @Test
    void followedOnlyModeShowsExactlyTheFollowedCreatorsOnceTheyAreFollowed() throws Exception {
        UUID fromA = publish(creatorA, Duration.ofHours(3), Set.of());
        publish(creatorB, Duration.ofHours(1), Set.of());
        useWeights("{\"mode\":\"FOLLOWING_ONLY\"}");
        assertThat(JsonPath.<List<String>>read(feed(), "$.items[*].videoId")).isEmpty();

        call(put("/v1/users/" + creatorA + "/follow"));
        waitFor(() -> JsonPath.<List<String>>read(feed(), "$.items[*].videoId").size() == 1);
        assertThat(JsonPath.<List<String>>read(feed(), "$.items[*].videoId")).containsExactly(fromA.toString());

        call(delete("/v1/users/" + creatorA + "/follow"));
        waitFor(() -> JsonPath.<List<String>>read(feed(), "$.items[*].videoId").isEmpty());
    }

    @Test
    void likingAVideoShowsAsLikedRaisesItsCounterAndTeachesTheFeedTheViewersTaste() throws Exception {
        UUID liked = publish(creatorA, Duration.ofHours(5), Set.of("afrobeats", "dance"));
        UUID sameTaste = publish(creatorB, Duration.ofHours(6), Set.of("afrobeats"));
        UUID otherTaste = publish(creatorB, Duration.ofHours(4), Set.of("news"));
        useWeights("{\"mode\":\"DEFAULT\",\"weights\":{\"TOPIC\":1,\"RECENCY\":0,\"ENGAGEMENT\":0,\"FOLLOWING\":0}}");
        assertThat(component(feed(), sameTaste, "TOPIC").doubleValue()).as("no taste yet").isZero();

        call(put("/v1/videos/" + liked + "/like"));
        counters.refresh(); // make the counter catch up now instead of waiting for the schedule
        waitFor(() -> {
            String json = feed();
            return component(json, sameTaste, "TOPIC").doubleValue() == 1.0
                    && JsonPath.<List<Number>>read(json, "$.items[?(@.videoId=='" + liked + "')].likes").getFirst().longValue() == 1L;
        });

        String json = feed();
        assertThat(component(json, sameTaste, "TOPIC").doubleValue()).as("shares the liked video's tag").isEqualTo(1.0);
        assertThat(component(json, otherTaste, "TOPIC").doubleValue()).isZero();
        assertThat(JsonPath.<List<Boolean>>read(json, "$.items[?(@.videoId=='" + liked + "')].liked").getFirst()).isTrue();
        assertThat(JsonPath.<List<Boolean>>read(json, "$.items[?(@.videoId=='" + sameTaste + "')].liked").getFirst()).isFalse();
        assertThat(JsonPath.<List<String>>read(json, "$.items[*].videoId")).first().isIn(liked.toString(), sameTaste.toString());

        call(delete("/v1/videos/" + liked + "/like"));
        waitFor(() -> component(feed(), sameTaste, "TOPIC").doubleValue() == 0.0);
        assertThat(JsonPath.<List<Boolean>>read(feed(), "$.items[?(@.videoId=='" + liked + "')].liked").getFirst()).isFalse();
    }

    @Test
    void theEngagementComponentFollowsTheRealCounters() throws Exception {
        UUID popular = publish(creatorA, Duration.ofHours(2), Set.of());
        UUID quiet = publish(creatorB, Duration.ofHours(2), Set.of());
        useWeights("{\"mode\":\"DEFAULT\",\"weights\":{\"ENGAGEMENT\":1,\"RECENCY\":0,\"FOLLOWING\":0,\"TOPIC\":0}}");
        assertThat(component(feed(), popular, "ENGAGEMENT").doubleValue()).isZero();

        // many different people like the popular video
        for (int i = 0; i < 40; i++) {
            UUID fan = UUID.randomUUID();
            mvc.perform(put("/v1/videos/" + popular + "/like")
                    .header("Authorization", "Bearer " + tokens.issue(fan, UUID.randomUUID()).value()))
                    .andExpect(status().isNoContent());
        }
        counters.refresh();
        waitFor(() -> component(feed(), popular, "ENGAGEMENT").doubleValue() > 0.5);

        String json = feed();
        assertThat(JsonPath.<List<String>>read(json, "$.items[*].videoId")).first().isEqualTo(popular.toString());
        assertThat(component(json, quiet, "ENGAGEMENT").doubleValue()).isZero();
    }
}
