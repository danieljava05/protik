package com.protik.feed.demo;

import com.protik.feed.service.FeedIndexer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sample videos so the feed and its explanations can be seen before uploads exist.
 *
 * <p><strong>Off by default.</strong> It exists only when the application is started with
 * {@code protik.demo.seed=true}, and it writes only to the feed's own read model, so there are no real
 * videos, creators or files behind these entries. IDs are derived from fixed names, so running it again
 * refreshes the same rows (their ages move forward with the clock) instead of adding more.
 */
@Component
@ConditionalOnProperty(name = "protik.demo.seed", havingValue = "true")
class DemoData implements ApplicationRunner {

    static final int VIDEOS = 48;

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);
    private static final String[] LANGUAGES = {"yo", "ig", "ha", "pcm", "en"};
    private static final List<Set<String>> TAG_SETS = List.of(
            Set.of("afrobeats", "dance"), Set.of("comedy", "skit"), Set.of("football", "naija"),
            Set.of("food", "jollof"), Set.of("tech", "lagos"), Set.of("news"), Set.of("music", "afrobeats"),
            Set.of("comedy", "pidgin"), Set.of());

    private final FeedIndexer indexer;

    DemoData(FeedIndexer indexer) {
        this.indexer = indexer;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(Instant.now());
    }

    void seed(Instant now) {
        var random = new Random(2026); // fixed, so the sample set looks the same on every start
        List<UUID> creators = java.util.stream.IntStream.range(0, 8).mapToObj(i -> named("demo-creator-" + i)).toList();
        for (int i = 0; i < VIDEOS; i++) {
            UUID id = named("demo-video-" + i);
            // ages from 5 minutes to about 10 days, newest first
            Instant publishedAt = now.minus(Duration.ofMinutes(5L + (long) (Math.pow(i / (double) VIDEOS, 2) * 14_000)));
            indexer.videoPublished(id, creators.get(i % creators.size()), LANGUAGES[random.nextInt(LANGUAGES.length)],
                    publishedAt, 8_000 + random.nextInt(50_000), TAG_SETS.get(random.nextInt(TAG_SETS.size())));
            // a few videos are very popular, most are not
            boolean viral = random.nextInt(8) == 0;
            int scale = viral ? 40 : 1;
            indexer.statsChanged(id, random.nextInt(400) * scale, random.nextInt(60) * scale,
                    random.nextInt(6_000) * scale);
        }
        log.warn("Demo data is ON (protik.demo.seed=true): {} sample videos were added to the feed. "
                + "They are not real videos. Never enable this in production.", VIDEOS);
    }

    private static UUID named(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
