package com.protik.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestClockConfiguration;
import com.protik.TestClockConfiguration.SettableClock;
import com.protik.TestcontainersConfiguration;
import com.protik.feed.persistence.DataRepository;
import com.protik.feed.service.FeedIndexer;
import com.protik.identity.service.TokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** ADR 0018: data settings, reported usage, the billing-period budget, and the advice in the feed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class DataApiIntegrationTests {

    private static final long MB = 1_000_000L;
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    SettableClock clock;
    @Autowired
    FeedIndexer indexer;
    @Autowired
    DataRepository repository;

    private final UUID me = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        db.sql("DELETE FROM feed.usage_events").update();
        db.sql("DELETE FROM feed.user_feed_settings").update();
        db.sql("DELETE FROM feed.candidates").update();
        db.sql("DELETE FROM shared.rate_limits").update();
    }

    // ---- helpers ----

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private ResultActions getSettings(UUID user) throws Exception {
        return mvc.perform(get("/v1/data/settings").header("Authorization", bearer(user)));
    }

    private ResultActions putSettings(UUID user, String json) throws Exception {
        return mvc.perform(put("/v1/data/settings").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String event(UUID id, String rendition, long bytes, Instant at) {
        return "{\"id\":\"%s\",\"videoId\":\"%s\",\"rendition\":\"%s\",\"bytes\":%d,\"occurredAt\":\"%s\"}"
                .formatted(id, UUID.randomUUID(), rendition, bytes, at);
    }

    private static String batch(String... events) {
        return "{\"events\":[" + String.join(",", events) + "]}";
    }

    private ResultActions report(UUID user, String json) throws Exception {
        return mvc.perform(post("/v1/data/usage").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions summary(UUID user) throws Exception {
        return mvc.perform(get("/v1/data/usage").header("Authorization", bearer(user)));
    }

    private ResultActions reportBytes(UUID user, String rendition, long bytes, Instant at) throws Exception {
        return report(user, batch(event(UUID.randomUUID(), rendition, bytes, at)));
    }

    private long used(UUID user) throws Exception {
        return JsonPath.<Number>read(summary(user).andReturn().getResponse().getContentAsString(), "$.usedBytes").longValue();
    }

    // ---- settings ----

    @Test
    void aNewViewerHasSensibleDefaults() throws Exception {
        getSettings(me).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.dataSaver").value(false))
                .andExpect(jsonPath("$.monthlyCapMb").doesNotExist())
                .andExpect(jsonPath("$.prefetchCapMb").value(5))
                .andExpect(jsonPath("$.billingDay").value(1));
        assertThat(db.sql("SELECT count(*) FROM feed.user_feed_settings").query(Long.class).single())
                .as("reading creates nothing").isZero();
    }

    @Test
    void settingsRoundTripAndAreReplacedNotMerged() throws Exception {
        putSettings(me, "{\"dataSaver\":true,\"monthlyCapMb\":1500,\"prefetchCapMb\":2,\"billingDay\":17}")
                .andExpect(status().isOk());
        getSettings(me).andExpect(jsonPath("$.dataSaver").value(true)).andExpect(jsonPath("$.monthlyCapMb").value(1500))
                .andExpect(jsonPath("$.prefetchCapMb").value(2)).andExpect(jsonPath("$.billingDay").value(17));
        putSettings(me, "{\"dataSaver\":false,\"prefetchCapMb\":5}").andExpect(status().isOk());
        getSettings(me).andExpect(jsonPath("$.dataSaver").value(false)).andExpect(jsonPath("$.monthlyCapMb").doesNotExist())
                .andExpect(jsonPath("$.billingDay").value(1));
    }

    @Test
    void dataSettingsAndRankingChoicesDoNotOverwriteEachOther() throws Exception {
        mvc.perform(put("/v1/feed/preferences").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"CHRONOLOGICAL\"}")).andExpect(status().isOk());
        putSettings(me, "{\"dataSaver\":true,\"monthlyCapMb\":500,\"prefetchCapMb\":1,\"billingDay\":9}").andExpect(status().isOk());
        mvc.perform(get("/v1/feed/preferences").header("Authorization", bearer(me))).andExpect(jsonPath("$.mode").value("CHRONOLOGICAL"));

        mvc.perform(put("/v1/feed/preferences").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"TOPIC_FOCUSED\"}")).andExpect(status().isOk());
        getSettings(me).andExpect(jsonPath("$.monthlyCapMb").value(500)).andExpect(jsonPath("$.billingDay").value(9));
    }

    @Test
    void invalidSettingsAreRefusedAndChangeNothing() throws Exception {
        putSettings(me, "{\"dataSaver\":true,\"monthlyCapMb\":500,\"prefetchCapMb\":1,\"billingDay\":9}").andExpect(status().isOk());
        for (String bad : List.of(
                "{\"monthlyCapMb\":0,\"prefetchCapMb\":5,\"billingDay\":1}",
                "{\"monthlyCapMb\":-5,\"prefetchCapMb\":5,\"billingDay\":1}",
                "{\"monthlyCapMb\":1000001,\"prefetchCapMb\":5,\"billingDay\":1}",
                "{\"prefetchCapMb\":-1,\"billingDay\":1}",
                "{\"prefetchCapMb\":1001,\"billingDay\":1}",
                "{\"prefetchCapMb\":5,\"billingDay\":29}",
                "{\"prefetchCapMb\":5,\"billingDay\":-3}",
                "{\"prefetchCapMb\":\"lots\"}", "{not json")) {
            putSettings(me, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
        getSettings(me).andExpect(jsonPath("$.monthlyCapMb").value(500)).andExpect(jsonPath("$.billingDay").value(9));
    }

    // ---- reporting usage ----

    @Test
    void reportedBytesAreSummedByRenditionForThePeriod() throws Exception {
        report(me, batch(event(UUID.randomUUID(), "144p", 3 * MB, NOW.minusSeconds(60)),
                event(UUID.randomUUID(), "144p", 2 * MB, NOW.minusSeconds(30)),
                event(UUID.randomUUID(), "audio", 1 * MB, NOW.minusSeconds(10))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(3)).andExpect(jsonPath("$.ignoredDuplicates").value(0));
        summary(me).andExpect(jsonPath("$.usedBytes").value(6 * MB))
                .andExpect(jsonPath("$.byRendition['144p']").value(5 * MB)).andExpect(jsonPath("$.byRendition.audio").value(1 * MB))
                .andExpect(jsonPath("$.capBytes").doesNotExist()).andExpect(jsonPath("$.policy.state").value("UNLIMITED"));
    }

    @Test
    void resendingABatchCountsNothingTwice() throws Exception {
        String b = batch(event(UUID.randomUUID(), "240p", 4 * MB, NOW.minusSeconds(5)),
                event(UUID.randomUUID(), "240p", 4 * MB, NOW.minusSeconds(4)));
        report(me, b).andExpect(jsonPath("$.accepted").value(2));
        report(me, b).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.ignoredDuplicates").value(2));
        assertThat(used(me)).isEqualTo(8 * MB);
    }

    @Test
    void tenSimultaneousRetriesOfTheSameBatchStillCountOnce() throws Exception {
        String b = batch(event(UUID.randomUUID(), "480p", 7 * MB, NOW.minusSeconds(5)),
                event(UUID.randomUUID(), "480p", 3 * MB, NOW.minusSeconds(4)));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return report(me, b).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (var f : results) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdownNow();
        assertThat(used(me)).as("racing retries add the events exactly once").isEqualTo(10 * MB);
    }

    @Test
    void badReportsAreRefusedAndRecordNothing() throws Exception {
        UUID id = UUID.randomUUID();
        List<String> bad = new ArrayList<>(List.of(
                batch(event(id, "8k", MB, NOW)),                                                  // unknown rendition
                batch(event(id, "144p", -1, NOW)),                                                // negative bytes
                batch(event(id, "144p", (1L << 30) + 1, NOW)),                                    // more than 1 GiB in one event
                batch(event(id, "144p", MB, NOW.plus(Duration.ofMinutes(6)))),                    // from the future
                batch(event(id, "144p", MB, NOW.minus(Duration.ofDays(41)))),                     // too old
                "{\"events\":[]}", "{}", "{\"events\":[{\"id\":\"" + id + "\"}]}", "{not json"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(event(UUID.randomUUID(), "144p", 1, NOW));
        }
        bad.add(batch(many.toArray(String[]::new)));
        for (String json : bad) {
            report(me, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
        assertThat(db.sql("SELECT count(*) FROM feed.usage_events").query(Long.class).single()).isZero();
        // exactly at the edges is fine
        report(me, batch(event(UUID.randomUUID(), "144p", 1L << 30, NOW.plus(Duration.ofMinutes(4))),
                event(UUID.randomUUID(), "audio", 0, NOW.minus(Duration.ofDays(39))))).andExpect(status().isOk());
        List<String> hundred = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundred.add(event(UUID.randomUUID(), "144p", 1, NOW));
        }
        report(me, batch(hundred.toArray(String[]::new))).andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(100));
    }

    @Test
    void oneBadEventRejectsTheWholeBatchSoNothingIsHalfCounted() throws Exception {
        report(me, batch(event(UUID.randomUUID(), "144p", MB, NOW), event(UUID.randomUUID(), "bogus", MB, NOW)))
                .andExpect(status().isBadRequest());
        assertThat(used(me)).isZero();
    }

    @Test
    void reportingTooOftenIsStopped() throws Exception {
        UUID spammer = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            reportBytes(spammer, "144p", 1, NOW).andExpect(status().isOk());
        }
        reportBytes(spammer, "144p", 1, NOW).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        reportBytes(UUID.randomUUID(), "144p", 1, NOW).andExpect(status().isOk());
    }

    @Test
    void viewersOnlySeeTheirOwnUsage() throws Exception {
        UUID other = UUID.randomUUID();
        reportBytes(other, "720p", 50 * MB, NOW.minusSeconds(10)).andExpect(status().isOk());
        assertThat(used(me)).isZero();
        assertThat(used(other)).isEqualTo(50 * MB);
    }

    // ---- the billing period ----

    @Test
    void onlyUsageInsideTheCurrentBillingPeriodCounts() throws Exception {
        putSettings(me, "{\"dataSaver\":false,\"monthlyCapMb\":100,\"prefetchCapMb\":5,\"billingDay\":15}").andExpect(status().isOk());
        reportBytes(me, "480p", 10 * MB, Instant.parse("2026-10-16T08:00:00Z")).andExpect(status().isOk()); // this period
        reportBytes(me, "480p", 20 * MB, Instant.parse("2026-10-14T23:59:59Z")).andExpect(status().isOk()); // the one before
        summary(me).andExpect(jsonPath("$.usedBytes").value(10 * MB)).andExpect(jsonPath("$.periodStart").value("2026-10-15"))
                .andExpect(jsonPath("$.periodEnd").value("2026-11-15")).andExpect(jsonPath("$.capBytes").value(100 * MB));

        clock.set(Instant.parse("2026-11-15T00:00:00Z")); // the plan renews
        summary(me).andExpect(jsonPath("$.usedBytes").value(0)).andExpect(jsonPath("$.periodStart").value("2026-11-15"));
        clock.set(Instant.parse("2026-11-14T23:59:59Z"));
        summary(me).andExpect(jsonPath("$.usedBytes").value(10 * MB)).andExpect(jsonPath("$.periodStart").value("2026-10-15"));
    }

    // ---- the advice ----

    @Test
    void theAdviceTightensAsTheBudgetIsUsed() throws Exception {
        putSettings(me, "{\"dataSaver\":false,\"monthlyCapMb\":10,\"prefetchCapMb\":4,\"billingDay\":1}").andExpect(status().isOk());

        reportBytes(me, "240p", 5 * MB, NOW.minusSeconds(60));
        summary(me).andExpect(jsonPath("$.policy.state").value("OK")).andExpect(jsonPath("$.policy.recommendedRendition").doesNotExist())
                .andExpect(jsonPath("$.policy.prefetchBudgetBytes").value(4 * MB)).andExpect(jsonPath("$.policy.remainingBytes").value(5 * MB));

        reportBytes(me, "240p", 4_500_000, NOW.minusSeconds(50)); // 9.5 MB of 10
        summary(me).andExpect(jsonPath("$.policy.state").value("LOW")).andExpect(jsonPath("$.policy.recommendedRendition").value("144p"))
                .andExpect(jsonPath("$.policy.prefetchBudgetBytes").value(500_000));

        reportBytes(me, "audio", 500_000, NOW.minusSeconds(40)); // exactly 10 MB
        summary(me).andExpect(jsonPath("$.policy.state").value("EXHAUSTED")).andExpect(jsonPath("$.policy.recommendedRendition").value("audio"))
                .andExpect(jsonPath("$.policy.prefetchBudgetBytes").value(0)).andExpect(jsonPath("$.policy.remainingBytes").value(0));
    }

    @Test
    void theFeedCarriesTheAdviceAndWhatEachVideoWouldCost() throws Exception {
        UUID video = UUID.randomUUID();
        indexer.videoPublished(video, UUID.randomUUID(), "yo", Instant.now().minusSeconds(60), 60_000, Set.of());
        putSettings(me, "{\"dataSaver\":true,\"monthlyCapMb\":200,\"prefetchCapMb\":5,\"billingDay\":1}").andExpect(status().isOk());

        String json = mvc.perform(get("/v1/feed").header("Authorization", bearer(me))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(json, "$.dataPolicy.state")).isEqualTo("OK");
        assertThat(JsonPath.<String>read(json, "$.dataPolicy.recommendedRendition")).isEqualTo("144p");
        assertThat(JsonPath.<Number>read(json, "$.dataPolicy.prefetchBudgetBytes").longValue()).as("data saver: no prefetch").isZero();
        // a one-minute video: bitrate (kbit/s) x 125 bytes x 60 s, cheapest first
        assertThat(JsonPath.<Number>read(json, "$.items[0].estimatedBytes.audio").longValue()).isEqualTo(360_000L);
        assertThat(JsonPath.<Number>read(json, "$.items[0].estimatedBytes['144p']").longValue()).isEqualTo(1_125_000L);
        assertThat(JsonPath.<Number>read(json, "$.items[0].estimatedBytes['720p']").longValue()).isEqualTo(13_500_000L);
        assertThat(JsonPath.<java.util.Map<String, Object>>read(json, "$.items[0].estimatedBytes").keySet())
                .containsExactly("audio", "144p", "240p", "480p", "720p");

        String lean = mvc.perform(get("/v1/feed?estimates=false&explain=false").header("Authorization", bearer(me)))
                .andReturn().getResponse().getContentAsString();
        assertThat(lean).doesNotContain("estimatedBytes").doesNotContain("explanation").contains("dataPolicy");
        // one item, so the fixed part of the page (time, mode, advice) dominates; the optional parts are still a big share
        assertThat(lean.length()).isLessThan((int) (json.length() * 0.65));
    }

    @Test
    void usedUpBudgetShowsInTheFeedToo() throws Exception {
        indexer.videoPublished(UUID.randomUUID(), UUID.randomUUID(), "en", Instant.now().minusSeconds(60), 30_000, Set.of());
        putSettings(me, "{\"dataSaver\":false,\"monthlyCapMb\":1,\"prefetchCapMb\":5,\"billingDay\":1}").andExpect(status().isOk());
        reportBytes(me, "144p", 2 * MB, NOW.minusSeconds(5)).andExpect(status().isOk());
        mvc.perform(get("/v1/feed").header("Authorization", bearer(me))).andExpect(jsonPath("$.dataPolicy.state").value("EXHAUSTED"))
                .andExpect(jsonPath("$.dataPolicy.recommendedRendition").value("audio"));
    }

    // ---- privacy ----

    @Test
    void usageOlderThanFourHundredDaysIsDeleted() {
        UUID user = UUID.randomUUID();
        db.sql("INSERT INTO feed.usage_events (id, user_id, video_id, rendition_code, bytes, occurred_at) VALUES "
                + "(gen_random_uuid(), :u, gen_random_uuid(), '144p', 100, now() - interval '401 days'),"
                + "(gen_random_uuid(), :u, gen_random_uuid(), '144p', 100, now() - interval '399 days'),"
                + "(gen_random_uuid(), :u, gen_random_uuid(), '144p', 100, now())").param("u", user).update();
        assertThat(repository.purgeUsageOlderThan(Duration.ofDays(400))).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM feed.usage_events WHERE user_id = :u").param("u", user).query(Long.class).single())
                .isEqualTo(2);
    }

    // ---- access ----

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        mvc.perform(get("/v1/data/settings")).andExpect(status().isUnauthorized());
        mvc.perform(put("/v1/data/settings").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/data/usage").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/data/usage")).andExpect(status().isUnauthorized());
    }
}
