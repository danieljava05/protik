package com.protik.video.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.protik.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Sample videos, switched on explicitly, travel the real path: video row, event, feed. */
@SpringBootTest(properties = "protik.demo.seed=true")
@Import(TestcontainersConfiguration.class)
class DemoVideosTests {

    @Autowired
    DemoVideos demoVideos;
    @Autowired
    JdbcClient db;

    private long count(String sql) {
        return db.sql(sql).query(Long.class).single();
    }

    @Test
    void sampleVideosAreRealRowsInTheVideoModuleAndReachTheFeedThroughEvents() {
        demoVideos.seed(Instant.now());
        assertThat(count("SELECT count(*) FROM video.videos WHERE description = 'Sample video' AND status = 'PUBLISHED'"))
                .isGreaterThanOrEqualTo(DemoVideos.VIDEOS);
        // nothing wrote to the feed directly: it learns from VideoPublished
        await().atMost(Duration.ofSeconds(60)).until(() ->
                count("SELECT count(*) FROM feed.candidates c WHERE EXISTS "
                        + "(SELECT 1 FROM video.videos v WHERE v.id = c.video_id AND v.description = 'Sample video')")
                        >= DemoVideos.VIDEOS);
    }

    @Test
    void repeatingTheSeedRefreshesTheSameRowsAndTheyStayInsideTheFeedWindow() {
        demoVideos.seed(Instant.now());
        long first = count("SELECT count(*) FROM video.videos WHERE description = 'Sample video'");
        demoVideos.seed(Instant.now());
        assertThat(count("SELECT count(*) FROM video.videos WHERE description = 'Sample video'")).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM video.videos WHERE description = 'Sample video' AND published_at > now()"))
                .isZero();
        assertThat(count("SELECT count(*) FROM video.videos WHERE description = 'Sample video' "
                + "AND published_at < now() - interval '14 days'")).as("all inside the feed's 14-day window").isZero();
        assertThat(count("SELECT count(DISTINCT creator_id) FROM video.videos WHERE description = 'Sample video'"))
                .isEqualTo(DemoVideos.CREATORS);
    }
}
