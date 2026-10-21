package com.protik.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.protik.TestcontainersConfiguration;
import com.protik.video.VideoPublished;
import com.protik.video.VideoRemoved;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR 0002 / ADR 0015: the video module announces, the feed reacts after commit, through the
 * persistent event registry, at least once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FeedEventsIntegrationTests {

    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    JdbcClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM feed.candidates").update();
        db.sql("DELETE FROM shared.event_publication").update();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> work.run());
    }

    private long candidates(UUID id) {
        return db.sql("SELECT count(*) FROM feed.candidates WHERE video_id = :id").param("id", id).query(Long.class).single();
    }

    private static VideoPublished published(UUID id, int durationMs, Set<String> tags) {
        return new VideoPublished(id, UUID.randomUUID(), Instant.now().minusSeconds(60), durationMs, "ig", tags);
    }

    @Test
    void aPublishedVideoReachesTheFeedAfterTheCommit() {
        UUID id = UUID.randomUUID();
        inTransaction(() -> events.publishEvent(published(id, 20_000, Set.of("#Comedy", "naija"))));

        await().atMost(Duration.ofSeconds(30)).until(() -> candidates(id) == 1);
        assertThat(db.sql("SELECT tag FROM feed.candidate_tags WHERE video_id = :id ORDER BY tag").param("id", id)
                .query(String.class).list()).containsExactly("comedy", "naija");
        assertThat(db.sql("SELECT language FROM feed.candidates WHERE video_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("ig");
        assertThat(db.sql("SELECT count(*) FROM feed.candidate_stats WHERE video_id = :id").param("id", id)
                .query(Long.class).single()).as("a stats row exists from the start").isEqualTo(1);
    }

    @Test
    void handledEventsAreRemovedFromTheRegistry() {
        UUID id = UUID.randomUUID();
        inTransaction(() -> events.publishEvent(published(id, 20_000, Set.of())));
        await().atMost(Duration.ofSeconds(30)).until(() -> candidates(id) == 1);
        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT count(*) FROM shared.event_publication").query(Long.class).single() == 0);
    }

    @Test
    void anEventFromARolledBackTransactionNeverArrives() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            inTransaction(() -> {
                events.publishEvent(published(id, 20_000, Set.of()));
                throw new IllegalStateException("publishing the video failed");
            });
        } catch (IllegalStateException expected) {
            // the video was not published, so nobody must hear about it
        }
        Thread.sleep(1500);
        assertThat(candidates(id)).isZero();
        assertThat(db.sql("SELECT count(*) FROM shared.event_publication").query(Long.class).single()).isZero();
    }

    @Test
    void deliveringTheSameEventTwiceChangesNothingTheSecondTime() {
        UUID id = UUID.randomUUID();
        var event = published(id, 20_000, Set.of("music"));
        inTransaction(() -> events.publishEvent(event));
        await().atMost(Duration.ofSeconds(30)).until(() -> candidates(id) == 1);

        inTransaction(() -> events.publishEvent(event)); // at-least-once delivery means repeats happen
        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT count(*) FROM shared.event_publication").query(Long.class).single() == 0);
        assertThat(candidates(id)).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM feed.candidate_tags WHERE video_id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aRepublishedVideoUpdatesItsTags() {
        UUID id = UUID.randomUUID();
        inTransaction(() -> events.publishEvent(published(id, 20_000, Set.of("old"))));
        await().atMost(Duration.ofSeconds(30)).until(() -> candidates(id) == 1);
        inTransaction(() -> events.publishEvent(published(id, 20_000, Set.of("new"))));
        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT tag FROM feed.candidate_tags WHERE video_id = :id").param("id", id)
                        .query(String.class).list().equals(List.of("new")));
    }

    @Test
    void aRemovedVideoLeavesTheFeed() {
        UUID id = UUID.randomUUID();
        inTransaction(() -> events.publishEvent(published(id, 20_000, Set.of("x"))));
        await().atMost(Duration.ofSeconds(30)).until(() -> candidates(id) == 1);
        inTransaction(() -> events.publishEvent(new VideoRemoved(id)));
        await().atMost(Duration.ofSeconds(30)).until(() -> candidates(id) == 0);
        assertThat(db.sql("SELECT count(*) FROM feed.candidate_tags WHERE video_id = :id").param("id", id)
                .query(Long.class).single()).as("tags and stats go with it").isZero();
    }

    @Test
    void aPoisonEventIsKeptInTheRegistryAndDoesNotBlockTheOthers() {
        UUID poison = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        // duration 0 violates the table's CHECK, so this listener keeps failing
        inTransaction(() -> events.publishEvent(published(poison, 0, Set.of())));
        inTransaction(() -> events.publishEvent(published(good, 20_000, Set.of())));

        await().atMost(Duration.ofSeconds(30)).until(() -> candidates(good) == 1);
        assertThat(candidates(poison)).isZero();
        await().atMost(Duration.ofSeconds(30)).until(() ->
                db.sql("SELECT count(*) FROM shared.event_publication WHERE completion_date IS NULL")
                        .query(Long.class).single() == 1);
        assertThat(db.sql("SELECT serialized_event FROM shared.event_publication WHERE completion_date IS NULL")
                .query(String.class).single()).contains(poison.toString())
                .as("kept for retry and inspection, not silently dropped");
    }
}
