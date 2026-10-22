package com.protik.engagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.protik.TestcontainersConfiguration;
import com.protik.engagement.service.LikeService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Nobody calls refresh() here: the scheduled refresher alone must bring counters up to date. */
@SpringBootTest(properties = "protik.engagement.refresh-interval=PT0.2S")
@Import(TestcontainersConfiguration.class)
class CounterSchedulerTests {

    @Autowired
    LikeService likes;
    @Autowired
    JdbcClient db;

    @Test
    void theBackgroundRefresherCatchesCountersUpOnItsOwnAndAnnouncesThemToTheFeed() {
        UUID video = UUID.randomUUID();
        db.sql("INSERT INTO video.videos (id, creator_id, description, status, published_at, duration_ms) "
                + "VALUES (:id, gen_random_uuid(), 'scheduler-test', 'PUBLISHED', now(), 9000)").param("id", video).update();
        db.sql("INSERT INTO feed.candidates (video_id, creator_id, published_at, duration_ms) "
                + "VALUES (:id, gen_random_uuid(), now(), 9000)").param("id", video).update();
        db.sql("INSERT INTO feed.candidate_stats (video_id, as_of) VALUES (:id, 'epoch')").param("id", video).update();

        for (int i = 0; i < 5; i++) {
            likes.like(UUID.randomUUID(), video);
        }

        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT COALESCE((SELECT likes FROM engagement.video_counters WHERE video_id = :v), 0)")
                        .param("v", video).query(Long.class).single() == 5);
        // ...and the feed's own copy follows through the event registry
        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT likes FROM feed.candidate_stats WHERE video_id = :v").param("v", video)
                        .query(Long.class).single() == 5);

        db.sql("DELETE FROM video.videos WHERE id = :id").param("id", video).update();
        db.sql("DELETE FROM feed.candidates WHERE video_id = :id").param("id", video).update();
        assertThat(db.sql("SELECT count(*) FROM engagement.dirty_counters WHERE video_id = :v").param("v", video)
                .query(Long.class).single()).as("nothing left dirty").isZero();
    }
}
