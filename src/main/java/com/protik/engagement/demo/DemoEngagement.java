package com.protik.engagement.demo;

import com.protik.engagement.VideoStatsChanged;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plausible counters for the sample videos, so the ENGAGEMENT component has something to show.
 *
 * <p><strong>Off by default</strong> ({@code protik.demo.seed=true}). The numbers are invented: no person
 * liked these videos, so the hourly reconciliation (which recounts from the real like rows) will zero them.
 * That is expected for sample data.
 */
@Component
@Order(3)
@ConditionalOnProperty(name = "protik.demo.seed", havingValue = "true")
class DemoEngagement implements ApplicationRunner {

    static final int VIDEOS = 48;

    private static final Logger log = LoggerFactory.getLogger(DemoEngagement.class);

    private final JdbcClient db;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    DemoEngagement(JdbcClient db, ApplicationEventPublisher events, TransactionTemplate tx) {
        this.db = db;
        this.events = events;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    void seed() {
        var random = new Random(2027);
        for (int i = 0; i < VIDEOS; i++) {
            UUID id = UUID.nameUUIDFromBytes(("demo-video-" + i).getBytes(StandardCharsets.UTF_8));
            boolean viral = random.nextInt(8) == 0;
            int scale = viral ? 40 : 1; // a few videos are very popular, most are not
            long likes = random.nextInt(400) * scale;
            long comments = random.nextInt(60) * scale;
            long views = random.nextInt(6_000) * scale;
            tx.executeWithoutResult(status -> {
                db.sql("""
                        INSERT INTO engagement.video_counters (video_id, likes, comments, views) VALUES (:id, :l, :c, :v)
                        ON CONFLICT (video_id) DO UPDATE SET likes = :l, comments = :c, views = :v, updated_at = now()
                        """)
                        .param("id", id).param("l", likes).param("c", comments).param("v", views).update();
                events.publishEvent(new VideoStatsChanged(id, likes, comments, views, Instant.now()));
            });
        }
        log.warn("Demo data is ON (protik.demo.seed=true): invented counters for {} sample videos.", VIDEOS);
    }
}
