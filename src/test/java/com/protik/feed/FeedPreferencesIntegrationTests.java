package com.protik.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.protik.TestcontainersConfiguration;
import com.protik.feed.service.FeedPreferencesService;
import com.protik.identity.service.TokenService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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

/** T2.2: the viewer can see how ranking works, choose a mode and adjust weights. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FeedPreferencesIntegrationTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;
    @Autowired
    FeedPreferencesService preferences;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM feed.user_feed_settings").update(); // cascades to the overrides
    }

    // ---- helpers ----

    private String bearer(UUID user) {
        return "Bearer " + tokens.issue(user, UUID.randomUUID()).value();
    }

    private ResultActions getPreferences(UUID user) throws Exception {
        return mvc.perform(get("/v1/feed/preferences").header("Authorization", bearer(user)));
    }

    private ResultActions putPreferences(UUID user, String body) throws Exception {
        return mvc.perform(put("/v1/feed/preferences").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }


    // ---- reading the rules ----

    @Test
    void everyModeIsListedWithItsPublishedWeights() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(get("/v1/feed/modes").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.code=='DEFAULT')].weights.RECENCY").value(0.3))
                .andExpect(jsonPath("$[?(@.code=='CHRONOLOGICAL')].weights.RECENCY").value(1.0))
                .andExpect(jsonPath("$[?(@.code=='CHRONOLOGICAL')].weights.ENGAGEMENT").value(0.0))
                .andExpect(jsonPath("$[?(@.code=='FOLLOWING_ONLY')].followedOnly").value(true))
                .andExpect(jsonPath("$[?(@.code=='DEFAULT')].followedOnly").value(false));
    }

    @Test
    void aViewerWhoNeverChoseGetsTheDefaultModeAsPublished() throws Exception {
        getPreferences(UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.mode").value("DEFAULT"))
                .andExpect(jsonPath("$.followedOnly").value(false))
                .andExpect(jsonPath("$.components.length()").value(4))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].override").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].effectiveWeight").value(0.3))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].description").isNotEmpty());
        assertThat(db.sql("SELECT count(*) FROM feed.user_feed_settings").query(Long.class).single())
                .as("reading creates nothing").isZero();
    }

    // ---- choosing ----

    @Test
    void choosingAModeAndAdjustingWeightsIsReflectedInTheEffectiveWeights() throws Exception {
        UUID user = UUID.randomUUID();
        putPreferences(user, "{\"mode\":\"TOPIC_FOCUSED\",\"weights\":{\"RECENCY\":0.9,\"FOLLOWING\":0}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TOPIC_FOCUSED"))
                .andExpect(jsonPath("$.components[?(@.code=='TOPIC')].effectiveWeight").value(0.5))       // from the mode
                .andExpect(jsonPath("$.components[?(@.code=='TOPIC')].override").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].modeWeight").value(0.2))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].override").value(0.9))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].effectiveWeight").value(0.9))      // overridden
                .andExpect(jsonPath("$.components[?(@.code=='FOLLOWING')].effectiveWeight").value(0.0));    // zero is a real choice
        getPreferences(user).andExpect(jsonPath("$.mode").value("TOPIC_FOCUSED"))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].effectiveWeight").value(0.9));
    }

    @Test
    void savingReplacesEarlierOverridesAndEmptyMeansUseTheModeAsPublished() throws Exception {
        UUID user = UUID.randomUUID();
        putPreferences(user, "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":1,\"TOPIC\":1}}").andExpect(status().isOk());
        putPreferences(user, "{\"mode\":\"DEFAULT\",\"weights\":{\"ENGAGEMENT\":0.7}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].override").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.components[?(@.code=='ENGAGEMENT')].effectiveWeight").value(0.7));
        putPreferences(user, "{\"mode\":\"CHRONOLOGICAL\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.components[?(@.code=='ENGAGEMENT')].override").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].effectiveWeight").value(1.0));
        assertThat(db.sql("SELECT count(*) FROM feed.user_ranking_weights").query(Long.class).single()).isZero();
    }

    @Test
    void savingTheSameRequestTwiceGivesTheSameResult() throws Exception {
        UUID user = UUID.randomUUID();
        String body = "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0.25}}";
        String first = putPreferences(user, body).andReturn().getResponse().getContentAsString();
        String second = putPreferences(user, body).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(db.sql("SELECT count(*) FROM feed.user_ranking_weights").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void savingDoesNotTouchTheViewersOtherSettings() throws Exception {
        UUID user = UUID.randomUUID();
        db.sql("INSERT INTO feed.user_feed_settings (user_id, data_saver, monthly_data_cap_mb, prefetch_cap_mb) "
                + "VALUES (:u, true, 500, 9)").param("u", user).update();
        putPreferences(user, "{\"mode\":\"CHRONOLOGICAL\"}").andExpect(status().isOk());
        var row = db.sql("SELECT ranking_mode, data_saver, monthly_data_cap_mb, prefetch_cap_mb "
                + "FROM feed.user_feed_settings WHERE user_id = :u").param("u", user)
                .query((rs, n) -> List.of(rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getInt(4))).single();
        assertThat(row).containsExactly("CHRONOLOGICAL", true, 500, 9);
    }

    @Test
    void peopleDoNotSeeEachOthersChoices() throws Exception {
        UUID ada = UUID.randomUUID();
        UUID tunde = UUID.randomUUID();
        putPreferences(ada, "{\"mode\":\"FOLLOWING_ONLY\",\"weights\":{\"RECENCY\":0.1}}").andExpect(status().isOk());
        getPreferences(tunde).andExpect(jsonPath("$.mode").value("DEFAULT"))
                .andExpect(jsonPath("$.components[?(@.code=='RECENCY')].override").value(org.hamcrest.Matchers.contains((Object) null)));
        getPreferences(ada).andExpect(jsonPath("$.mode").value("FOLLOWING_ONLY")).andExpect(jsonPath("$.followedOnly").value(true));
    }

    // ---- refusing bad input ----

    @Test
    void invalidChoicesAreRefusedAndChangeNothing() throws Exception {
        UUID user = UUID.randomUUID();
        putPreferences(user, "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0.5}}").andExpect(status().isOk());

        for (String bad : List.of(
                "{\"mode\":\"MADE_UP\"}",                                         // unknown mode
                "{\"mode\":\"DEFAULT\",\"weights\":{\"VIBES\":0.5}}",             // unknown component
                "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":-0.1}}",          // below 0
                "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":1.001}}",         // above 1
                "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0.1234}}",        // more precision than stored
                "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":null}}",          // null weight
                "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":\"high\"}}",      // not a number
                "{\"mode\":\"\"}", "{}", "{not json")) {
            putPreferences(user, bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
        getPreferences(user).andExpect(jsonPath("$.components[?(@.code=='RECENCY')].override").value(0.5));
    }

    @Test
    void aWeightOfExactlyZeroAndOneAreAllowedAndTrailingZerosAreNotExtraPrecision() throws Exception {
        UUID user = UUID.randomUUID();
        putPreferences(user, "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0,\"ENGAGEMENT\":1,\"TOPIC\":0.5000}}")
                .andExpect(status().isOk());
    }

    @Test
    void anInvalidSaveRollsBackEarlierPartsOfTheSameSave() throws Exception {
        UUID user = UUID.randomUUID();
        putPreferences(user, "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0.5}}").andExpect(status().isOk());
        putPreferences(user, "{\"mode\":\"CHRONOLOGICAL\",\"weights\":{\"TOPIC\":0.2,\"RECENCY\":5}}")
                .andExpect(status().isBadRequest());
        getPreferences(user).andExpect(jsonPath("$.mode").value("DEFAULT"));
    }

    // ---- access ----

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        mvc.perform(get("/v1/feed/modes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/feed/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(put("/v1/feed/preferences").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"DEFAULT\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- what the ranker will use ----

    @Test
    void theEffectiveWeightsHandedToTheRankerAreTheOnesTheViewerSees() throws Exception {
        UUID user = UUID.randomUUID();
        putPreferences(user, "{\"mode\":\"DEFAULT\",\"weights\":{\"FOLLOWING\":1,\"TOPIC\":0}}").andExpect(status().isOk());
        var resolved = preferences.preferences(user);
        assertThat(resolved.effectiveWeights()).containsOnlyKeys("ENGAGEMENT", "FOLLOWING", "RECENCY", "TOPIC")
                .containsEntry("FOLLOWING", 1.0).containsEntry("TOPIC", 0.0)
                .containsEntry("RECENCY", 0.3).containsEntry("ENGAGEMENT", 0.3);
        assertThat(resolved.followedOnly()).isFalse();
    }

    @Test
    void everyComponentTheDatabaseDefinesHasAFunctionBehindItAndViceVersa(
            @Autowired List<ScoreFunction> functions) {
        Set<String> inDatabase = db.sql("SELECT code FROM feed.ranking_components").query(String.class).set();
        Set<String> implemented = functions.stream().map(ScoreFunction::code).collect(Collectors.toSet());
        assertThat(implemented).as("a component without a function (or the reverse) would never take effect")
                .isEqualTo(inDatabase);
    }

    // ---- concurrency ----

    @Test
    void simultaneousSavesByOneViewerNeverLeaveAMixOfTwoRequests() throws Exception {
        UUID user = UUID.randomUUID();
        String a = "{\"mode\":\"DEFAULT\",\"weights\":{\"RECENCY\":0.1,\"ENGAGEMENT\":0.2}}";
        String b = "{\"mode\":\"CHRONOLOGICAL\",\"weights\":{\"FOLLOWING\":0.3,\"TOPIC\":0.4}}";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String body = i % 2 == 0 ? a : b;
            results.add(pool.submit(() -> {
                go.await();
                return putPreferences(user, body).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (var f : results) {
            assertThat(f.get(120, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdownNow();

        Map<String, Object> state = Map.of(
                "mode", db.sql("SELECT ranking_mode FROM feed.user_feed_settings WHERE user_id = :u").param("u", user)
                        .query(String.class).single(),
                "overrides", db.sql("SELECT component_code FROM feed.user_ranking_weights WHERE user_id = :u ORDER BY 1")
                        .param("u", user).query(String.class).list());
        boolean isA = state.get("mode").equals("DEFAULT") && state.get("overrides").equals(List.of("ENGAGEMENT", "RECENCY"));
        boolean isB = state.get("mode").equals("CHRONOLOGICAL") && state.get("overrides").equals(List.of("FOLLOWING", "TOPIC"));
        assertThat(isA || isB).as("final state is exactly one request's choice, not a blend: %s", state).isTrue();
    }
}
