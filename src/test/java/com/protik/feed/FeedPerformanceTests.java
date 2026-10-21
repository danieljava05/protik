package com.protik.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protik.TestcontainersConfiguration;
import com.protik.identity.service.TokenService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Measures the feed request itself (candidate query, ranking, paging) on a realistic table size.
 * In-process over MockMvc against a local PostgreSQL: it excludes the network, TLS and a real server,
 * so it is a lower bound, not a latency claim. Hardware is recorded in DESIGN.md next to the numbers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FeedPerformanceTests {

    private static final int CANDIDATES = 20_000;
    private static final int REQUESTS = 200;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient db;
    @Autowired
    TokenService tokens;

    @Test
    void firstPageOfTheFeedIsFastOverTwentyThousandCandidates() throws Exception {
        db.sql("DELETE FROM feed.candidates").update();
        db.sql("DELETE FROM feed.following").update();
        UUID viewer = UUID.randomUUID();

        // 20,000 videos over the last 14 days from 2,000 creators, with counters and 0 to 5 tags each
        db.sql("""
                INSERT INTO feed.candidates (video_id, creator_id, language, published_at, duration_ms)
                SELECT gen_random_uuid(), ('00000000-0000-4000-8000-' || lpad((g % 2000)::text, 12, '0'))::uuid,
                       (ARRAY['yo','ig','ha','pcm','en'])[1 + g % 5],
                       now() - (random() * interval '13 days'), 5000 + (random() * 55000)::int
                FROM generate_series(1, :n) g
                """).param("n", CANDIDATES).update();
        db.sql("""
                UPDATE feed.candidate_stats s SET likes = (random() * 5000)::int, comments = (random() * 300)::int,
                       views = (random() * 90000)::int
                """).update();
        db.sql("""
                INSERT INTO feed.candidate_tags (video_id, tag)
                SELECT c.video_id, 'tag' || t FROM feed.candidates c, generate_series(0, 4) t WHERE random() < 0.5
                ON CONFLICT DO NOTHING
                """).update();
        db.sql("""
                INSERT INTO feed.following (user_id, creator_id)
                SELECT :u, ('00000000-0000-4000-8000-' || lpad((g * 7)::text, 12, '0'))::uuid
                FROM generate_series(1, 150) g
                """).param("u", viewer).update();
        db.sql("ANALYZE feed.candidates").update();
        db.sql("ANALYZE feed.candidate_tags").update();
        // candidate_stats rows exist only for candidates published through the indexer; backfill for the test
        db.sql("INSERT INTO feed.candidate_stats (video_id, likes, comments, views) "
                + "SELECT video_id, (random() * 5000)::int, (random() * 300)::int, (random() * 90000)::int "
                + "FROM feed.candidates ON CONFLICT (video_id) DO NOTHING").update();

        String authorization = "Bearer " + tokens.issue(viewer, UUID.randomUUID()).value();
        for (int i = 0; i < 30; i++) { // warm up the JVM and the connection pool
            mvc.perform(get("/v1/feed?limit=20").header("Authorization", authorization)).andExpect(status().isOk());
        }
        List<Long> micros = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            long start = System.nanoTime();
            mvc.perform(get("/v1/feed?limit=20").header("Authorization", authorization)).andExpect(status().isOk());
            micros.add((System.nanoTime() - start) / 1_000);
        }
        Collections.sort(micros);
        long p50 = micros.get(REQUESTS / 2) / 1000;
        long p95 = micros.get((int) (REQUESTS * 0.95)) / 1000;
        long p99 = micros.get((int) (REQUESTS * 0.99)) / 1000;
        System.out.printf("FEED-MEASUREMENT candidates=%d pool=500 requests=%d p50=%dms p95=%dms p99=%dms%n",
                CANDIDATES, REQUESTS, p50, p95, p99);
        assertThat(p99).as("a loose guard against regressions, not a latency claim").isLessThan(3_000);

        db.sql("DELETE FROM feed.candidates").update();
        db.sql("DELETE FROM feed.following").update();
    }
}
