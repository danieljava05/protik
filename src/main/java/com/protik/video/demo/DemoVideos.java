package com.protik.video.demo;

import com.protik.video.VideoPublished;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
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
 * Sample videos so the feed can be seen before uploads exist.
 *
 * <p><strong>Off by default.</strong> They exist only when the application is started with
 * {@code protik.demo.seed=true}. There are no files behind them. They follow the real path: a row in the
 * video module's own table plus a {@link VideoPublished} event, which the feed reacts to like it will for
 * a real upload. IDs come from fixed names, so repeating the seed refreshes the same rows (their ages move
 * forward with the clock) instead of adding more.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "protik.demo.seed", havingValue = "true")
class DemoVideos implements ApplicationRunner {

    static final int VIDEOS = 48;
    static final int CREATORS = 8;

    private static final Logger log = LoggerFactory.getLogger(DemoVideos.class);
    private static final String[] LANGUAGES = {"yo", "ig", "ha", "pcm", "en"};
    private static final List<Set<String>> TAG_SETS = List.of(
            Set.of("afrobeats", "dance"), Set.of("comedy", "skit"), Set.of("football", "naija"),
            Set.of("food", "jollof"), Set.of("tech", "lagos"), Set.of("news"), Set.of("music", "afrobeats"),
            Set.of("comedy", "pidgin"), Set.of());

    private final JdbcClient db;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    DemoVideos(JdbcClient db, ApplicationEventPublisher events, TransactionTemplate tx) {
        this.db = db;
        this.events = events;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(Instant.now());
    }

    static UUID videoId(int i) {
        return UUID.nameUUIDFromBytes(("demo-video-" + i).getBytes(StandardCharsets.UTF_8));
    }

    void seed(Instant now) {
        var random = new Random(2026); // fixed, so the sample set looks the same on every start
        List<UUID> creators = IntStream.range(0, CREATORS)
                .mapToObj(i -> UUID.nameUUIDFromBytes(("demo-creator-" + i).getBytes(StandardCharsets.UTF_8))).toList();
        for (int i = 0; i < VIDEOS; i++) {
            UUID id = videoId(i);
            UUID creator = creators.get(i % creators.size());
            // ages from 5 minutes to about 10 days, newest first
            Instant publishedAt = now.minus(Duration.ofMinutes(5L + (long) (Math.pow(i / (double) VIDEOS, 2) * 14_000)));
            String language = LANGUAGES[random.nextInt(LANGUAGES.length)];
            int durationMs = 8_000 + random.nextInt(50_000);
            Set<String> tags = TAG_SETS.get(random.nextInt(TAG_SETS.size()));
            tx.executeWithoutResult(status -> {
                db.sql("""
                        INSERT INTO video.videos (id, creator_id, description, spoken_language, duration_ms, status, published_at)
                        VALUES (:id, :creator, :description, :language, :duration, 'PUBLISHED', :published)
                        ON CONFLICT (id) DO UPDATE SET published_at = EXCLUDED.published_at, status = 'PUBLISHED'
                        """)
                        .param("id", id).param("creator", creator).param("description", "Sample video")
                        .param("language", language).param("duration", durationMs)
                        .param("published", publishedAt.atOffset(ZoneOffset.UTC)).update();
                events.publishEvent(new VideoPublished(id, creator, publishedAt, durationMs, language, tags));
            });
        }
        log.warn("Demo data is ON (protik.demo.seed=true): {} sample videos were published. "
                + "There are no files behind them. Never enable this in production.", VIDEOS);
    }
}
