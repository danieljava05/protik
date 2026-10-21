package com.protik.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.feed.service.FeedIndexer;
import com.protik.identity.service.TokenService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

/** T2.3: the feed endpoint, end to end over the feed's own read models. */
@SpringBootTest(properties = "protik.feed.pool-size=30")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FeedIntegrationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    FeedIndexer indexer;

    private final UUID viewer = UUID.randomUUID();

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM feed.candidates").update();   // cascades to stats and tags
        db.sql("DELETE FROM feed.following").update();
        db.sql("DELETE FROM feed.user_interests").update();
        db.sql("DELETE FROM feed.user_feed_settings").update();
    }

    // ---- helpers ----

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private ResultActions feed(UUID user, String query) throws Exception {
        return mvc.perform(get("/v1/feed" + query).header("Authorization", bearer(user)));
    }

    private ResultActions feed(String query) throws Exception {
        return feed(viewer, query);
    }

    private UUID publish(UUID creator, Duration age, Set<String> tags) {
        UUID id = UUID.randomUUID();
        indexer.videoPublished(id, creator, "yo", Instant.now().minus(age), 15_000, tags);
        return id;
    }

    private UUID publish(Duration age) {
        return publish(UUID.randomUUID(), age, Set.of());
    }

    private void setMode(UUID user, String body) throws Exception {
        mvc.perform(put("/v1/feed/preferences").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    private List<String> ids(String json) {
        return JsonPath.read(json, "$.items[*].videoId");
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    // ---- basics ----

    @Test
    void anEmptyFeedIsAnEmptyListNotAnError() throws Exception {
        feed("").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.mode").value("DEFAULT"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void everyItemCarriesAStructuredExplanationThatAddsUpToItsScore() throws Exception {
        publish(Duration.ofHours(1));
        publish(Duration.ofHours(30));
        String json = body(feed("").andExpect(status().isOk()));

        List<Number> scores = JsonPath.read(json, "$.items[*].score");
        assertThat(scores).hasSize(2);
        for (int i = 0; i < scores.size(); i++) {
            List<String> codes = JsonPath.read(json, "$.items[" + i + "].explanation[*].component");
            assertThat(codes).containsExactly("RECENCY", "ENGAGEMENT", "FOLLOWING", "TOPIC");
            List<Number> contributions = JsonPath.read(json, "$.items[" + i + "].explanation[*].contribution");
            assertThat(contributions.stream().mapToDouble(Number::doubleValue).sum())
                    .isCloseTo(scores.get(i).doubleValue(), org.assertj.core.data.Offset.offset(1e-9));
            List<Number> values = JsonPath.read(json, "$.items[" + i + "].explanation[*].value");
            List<Number> weights = JsonPath.read(json, "$.items[" + i + "].explanation[*].weight");
            for (int c = 0; c < values.size(); c++) {
                assertThat(contributions.get(c).doubleValue())
                        .isCloseTo(values.get(c).doubleValue() * weights.get(c).doubleValue(),
                                org.assertj.core.data.Offset.offset(1e-9));
            }
        }
    }

    @Test
    void explanationsCanBeLeftOutToSaveData() throws Exception {
        for (int i = 0; i < 10; i++) {
            publish(Duration.ofHours(i + 1));
        }
        String withExplanation = body(feed("?explain=true"));
        String without = body(feed("?explain=false"));
        assertThat(without).as("the field is absent, not null").doesNotContain("explanation");
        assertThat(without.length()).as("the leaner response is substantially smaller")
                .isLessThan(withExplanation.length() / 2);
        assertThat(ids(without)).isEqualTo(ids(withExplanation));
    }

    @Test
    void itemsCarryWhatAClientNeedsToShowTheVideo() throws Exception {
        UUID creator = UUID.randomUUID();
        UUID id = publish(creator, Duration.ofHours(2), Set.of());
        feed("").andExpect(jsonPath("$.items[0].videoId").value(id.toString()))
                .andExpect(jsonPath("$.items[0].creatorId").value(creator.toString()))
                .andExpect(jsonPath("$.items[0].durationMs").value(15000))
                .andExpect(jsonPath("$.items[0].language").value("yo"))
                .andExpect(jsonPath("$.items[0].publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.asOf").isNotEmpty());
    }

    // ---- the viewer's settings drive the order ----

    @Test
    void theViewersWeightsDecideTheOrder() throws Exception {
        UUID fresh = publish(Duration.ofMinutes(5));
        UUID popular = publish(Duration.ofHours(60));
        indexer.statsChanged(popular, 4000, 600, 90_000);

        setMode(viewer, "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":1,\"ENGAGEMENT\":0}}");
        assertThat(ids(body(feed("")))).first().isEqualTo(fresh.toString());

        setMode(viewer, "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0.05,\"ENGAGEMENT\":1}}");
        assertThat(ids(body(feed("")))).first().isEqualTo(popular.toString());
    }

    @Test
    void chronologicalModeIsNewestFirst() throws Exception {
        UUID oldest = publish(Duration.ofHours(30));
        UUID newest = publish(Duration.ofHours(1));
        UUID middle = publish(Duration.ofHours(10));
        indexer.statsChanged(oldest, 99999, 99999, 99999);
        setMode(viewer, "{\"mode\":\"CHRONOLOGICAL\"}");
        assertThat(ids(body(feed("")))).containsExactly(newest.toString(), middle.toString(), oldest.toString());
    }

    @Test
    void followedOnlyModeShowsOnlyTheCreatorsTheViewerFollows() throws Exception {
        UUID friend = UUID.randomUUID();
        UUID fromFriend = publish(friend, Duration.ofHours(5), Set.of());
        publish(UUID.randomUUID(), Duration.ofMinutes(1), Set.of());
        indexer.followed(viewer, friend);

        setMode(viewer, "{\"mode\":\"FOLLOWING_ONLY\"}");
        assertThat(ids(body(feed("")))).containsExactly(fromFriend.toString());
        feed("").andExpect(jsonPath("$.mode").value("FOLLOWING_ONLY"));

        UUID loner = UUID.randomUUID();
        setMode(loner, "{\"mode\":\"FOLLOWING_ONLY\"}");
        feed(loner, "").andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void followingAndInterestsRaiseVideosTheViewerCaresAbout() throws Exception {
        UUID friend = UUID.randomUUID();
        UUID plain = publish(UUID.randomUUID(), Duration.ofHours(2), Set.of("news"));
        UUID followed = publish(friend, Duration.ofHours(3), Set.of("news"));
        UUID onTopic = publish(UUID.randomUUID(), Duration.ofHours(4), Set.of("afrobeats"));
        indexer.followed(viewer, friend);
        indexer.interestsChanged(viewer, Set.of("Afrobeats"));
        setMode(viewer, "{\"mode\":\"DEFAULT\",\"weights\":{\"FOLLOWING\":1,\"TOPIC\":0.9,\"RECENCY\":0.1,\"ENGAGEMENT\":0}}");

        String json = body(feed(""));
        assertThat(ids(json)).containsExactly(followed.toString(), onTopic.toString(), plain.toString());
        List<Number> following = JsonPath.read(json, "$.items[*].explanation[?(@.component=='FOLLOWING')].value");
        assertThat(following.stream().map(Number::doubleValue)).containsExactly(1.0, 0.0, 0.0);
        List<Number> topic = JsonPath.read(json, "$.items[*].explanation[?(@.component=='TOPIC')].value");
        assertThat(topic.stream().map(Number::doubleValue)).containsExactly(0.0, 1.0, 0.0);
    }

    @Test
    void viewersAreRankedIndependently() throws Exception {
        UUID fresh = publish(Duration.ofMinutes(5));
        UUID popular = publish(Duration.ofHours(60));
        indexer.statsChanged(popular, 4000, 600, 90_000);
        UUID other = UUID.randomUUID();
        setMode(viewer, "{\"mode\":\"CHRONOLOGICAL\"}");
        setMode(other, "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0.05,\"ENGAGEMENT\":1}}");
        assertThat(ids(body(feed(viewer, "")))).first().isEqualTo(fresh.toString());
        assertThat(ids(body(feed(other, "")))).first().isEqualTo(popular.toString());
    }

    // ---- the candidate pool ----

    @Test
    void videosOutsideTheTimeWindowAreNotConsidered() throws Exception {
        UUID recent = publish(Duration.ofDays(2));
        publish(Duration.ofDays(15)); // older than the 14-day window
        assertThat(ids(body(feed("")))).containsExactly(recent.toString());
    }

    @Test
    void onlyTheNewestVideosInThePoolAreRanked() throws Exception {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            all.add(publish(Duration.ofMinutes(i + 1)));        // all[0] is the newest
        }
        UUID viral = all.get(39);                                // the oldest, outside a pool of 30
        indexer.statsChanged(viral, 1_000_000, 1_000_000, 1_000_000);
        setMode(viewer, "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0,\"ENGAGEMENT\":1}}");

        List<String> served = new ArrayList<>();
        String cursor = null;
        do {
            String json = body(feed("?limit=50" + (cursor == null ? "" : "&cursor=" + cursor)));
            served.addAll(ids(json));
            cursor = JsonPath.read(json, "$.nextCursor");
        } while (cursor != null);
        assertThat(served).hasSize(30).doesNotContain(viral.toString());
    }

    @Test
    void followedOnlyModeIsNotCrowdedOutByStrangersNewerVideos() throws Exception {
        UUID friend = UUID.randomUUID();
        UUID fromFriend = publish(friend, Duration.ofDays(5), Set.of());
        for (int i = 0; i < 40; i++) {
            publish(UUID.randomUUID(), Duration.ofMinutes(i + 1), Set.of()); // more than the pool of 30
        }
        indexer.followed(viewer, friend);
        setMode(viewer, "{\"mode\":\"FOLLOWING_ONLY\"}");
        // the pool must be built from the followed creators' videos, not filtered afterwards
        assertThat(ids(body(feed("")))).containsExactly(fromFriend.toString());
    }

    @Test
    void aRemovedVideoDisappears() throws Exception {
        UUID keep = publish(Duration.ofHours(1));
        UUID gone = publish(Duration.ofHours(2));
        indexer.videoRemoved(gone);
        assertThat(ids(body(feed("")))).containsExactly(keep.toString());
    }

    // ---- paging ----

    @Test
    void pagesFollowEachOtherWithoutGapsOrRepeatsAndMatchOneBigPage() throws Exception {
        for (int i = 0; i < 25; i++) {
            UUID id = publish(Duration.ofMinutes(10 * (i + 1)));
            indexer.statsChanged(id, (i * 7) % 13 * 50, (i * 3) % 5, (i * 11) % 17 * 100);
        }
        List<String> everything = ids(body(feed("?limit=50")));
        assertThat(everything).hasSize(25).doesNotHaveDuplicates();

        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String json = body(feed("?limit=10" + (cursor == null ? "" : "&cursor=" + cursor)));
            paged.addAll(ids(json));
            cursor = JsonPath.read(json, "$.nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(paged).isEqualTo(everything);
    }

    @Test
    void aPageThatEndsExactlyAtTheEndHasNoCursor() throws Exception {
        for (int i = 0; i < 10; i++) {
            publish(Duration.ofHours(i + 1));
        }
        feed("?limit=10").andExpect(jsonPath("$.items.length()").value(10)).andExpect(jsonPath("$.nextCursor").doesNotExist());
        feed("?limit=9").andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }

    @Test
    void videosPublishedAfterTheSessionStartedDoNotShuffleLaterPages() throws Exception {
        for (int i = 0; i < 25; i++) {
            publish(Duration.ofHours(i + 2));
        }
        String first = body(feed("?limit=10"));
        String cursor = JsonPath.read(first, "$.nextCursor");

        UUID late = publish(Duration.ofSeconds(1)); // would be #1 in a fresh feed
        String second = body(feed("?limit=10&cursor=" + cursor));
        String third = body(feed("?limit=10&cursor=" + (String) JsonPath.read(second, "$.nextCursor")));

        List<String> session = new ArrayList<>(ids(first));
        session.addAll(ids(second));
        session.addAll(ids(third));
        assertThat(session).hasSize(25).doesNotHaveDuplicates().doesNotContain(late.toString());
        assertThat(ids(body(feed("?limit=1")))).as("a new session sees the new video").containsExactly(late.toString());
    }

    @Test
    void changingSettingsMidSessionInvalidatesTheCursorInsteadOfCorruptingTheOrder() throws Exception {
        for (int i = 0; i < 15; i++) {
            publish(Duration.ofHours(i + 1));
        }
        String cursor = JsonPath.read(body(feed("?limit=5")), "$.nextCursor");
        setMode(viewer, "{\"mode\":\"CHRONOLOGICAL\"}");
        feed("?limit=5&cursor=" + cursor).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:protik:problem:conflict"));
        feed("?limit=5").andExpect(status().isOk()); // starting over works
    }

    // ---- bad input ----

    @Test
    void badLimitsAreRefused() throws Exception {
        for (String limit : List.of("0", "-1", "51", "1000")) {
            feed("?limit=" + limit).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:protik:problem:validation-failed"));
        }
        feed("?limit=abc").andExpect(status().isBadRequest());
        feed("?limit=50").andExpect(status().isOk());
        feed("?limit=1").andExpect(status().isOk());
    }

    @Test
    void forgedOrMalformedCursorsAreRefused() throws Exception {
        publish(Duration.ofHours(1));
        String valid = "x";
        for (int i = 0; i < 12; i++) {
            publish(Duration.ofHours(i + 2));
        }
        valid = JsonPath.read(body(feed("?limit=3")), "$.nextCursor");
        String decoded = new String(Base64.getUrlDecoder().decode(valid), StandardCharsets.UTF_8);

        List<String> bad = List.of(
                "garbage", "", "a".repeat(400),
                Base64.getUrlEncoder().withoutPadding().encodeToString("v1.1.2.3".getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString(decoded.replace("v1.", "v9.").getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        decoded.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "not-a-uuid").getBytes(StandardCharsets.UTF_8)));
        for (String cursor : bad) {
            feed("?cursor=" + cursor).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:protik:problem:validation-failed"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }

    @Test
    void theFeedNeedsAToken() throws Exception {
        mvc.perform(get("/v1/feed")).andExpect(status().isUnauthorized());
    }
}
