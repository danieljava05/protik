package com.protik.feed.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Demo data, when explicitly switched on, is plausible and harmless to repeat. */
@SpringBootTest(properties = "protik.demo.seed=true")
@Import(TestcontainersConfiguration.class)
class DemoDataEnabledTests {

    @Autowired
    DemoData demoData;
    @Autowired
    JdbcClient db;

    @BeforeEach
    void reset() {
        db.sql("DELETE FROM feed.candidates").update();
    }

    private long count(String table) {
        return db.sql("SELECT count(*) FROM feed." + table).query(Long.class).single();
    }

    @Test
    void seedingAddsSampleVideosWithStatsAndTagsAndRepeatingItAddsNoMore() {
        demoData.seed(Instant.now());
        assertThat(count("candidates")).isEqualTo(DemoData.VIDEOS);
        demoData.seed(Instant.now());
        assertThat(count("candidates")).as("fixed names give fixed IDs: the same rows are refreshed").isEqualTo(DemoData.VIDEOS);
        assertThat(count("candidate_stats")).isEqualTo(DemoData.VIDEOS);
        assertThat(count("candidate_tags")).isPositive();
        assertThat(db.sql("SELECT count(DISTINCT creator_id) FROM feed.candidates").query(Long.class).single()).isEqualTo(8);
    }

    @Test
    void sampleVideosSpanMinutesToDaysAndNeverLieInTheFuture() {
        Instant now = Instant.now();
        demoData.seed(now);
        assertThat(db.sql("SELECT count(*) FROM feed.candidates WHERE published_at > now()").query(Long.class).single()).isZero();
        assertThat(db.sql("SELECT count(*) FROM feed.candidates WHERE published_at > now() - interval '1 hour'")
                .query(Long.class).single()).isPositive();
        assertThat(db.sql("SELECT count(*) FROM feed.candidates WHERE published_at < now() - interval '5 days'")
                .query(Long.class).single()).isPositive();
        assertThat(db.sql("SELECT count(*) FROM feed.candidates WHERE published_at < now() - interval '14 days'")
                .query(Long.class).single()).as("all inside the feed's 14-day window").isZero();
    }
}
