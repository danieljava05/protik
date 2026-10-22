package com.protik.feed.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The writer of the feed's read models: tidy input, idempotent, and cleans up after itself. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FeedIndexerTests {

    @Autowired
    FeedIndexer indexer;
    @Autowired
    JdbcClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM feed.candidates").update();
        db.sql("DELETE FROM feed.following").update();
        db.sql("DELETE FROM feed.user_interests").update();
    }

    @Test
    void tagsAreLowerCasedStrippedOfHashesDeduplicatedAndMalformedOnesDropped() {
        List<String> messy = new ArrayList<>(List.of("#Afrobeats", "afrobeats", "  Comedy  ", "##Naija", "two words",
                "", "#", "a".repeat(61), "ÒGÚN", "ok_tag-1"));
        messy.add(null);
        assertThat(FeedIndexer.normalizeTags(messy)).containsExactly("afrobeats", "comedy", "naija", "ògún", "ok_tag-1");
    }

    @Test
    void atMostTwentyTagsAreKept() {
        List<String> many = IntStream.range(0, 50).mapToObj(i -> "tag" + i).toList();
        assertThat(FeedIndexer.normalizeTags(many)).hasSize(FeedIndexer.MAX_TAGS).startsWith("tag0", "tag1");
    }

    @Test
    void publishingTwiceIsTheSameAsOnce() {
        UUID id = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        Instant when = Instant.parse("2026-10-19T10:00:00Z");
        indexer.videoPublished(id, creator, "ha", when, 12_000, Set.of("a", "b"));
        indexer.statsChanged(id, 5, 6, 7);
        indexer.videoPublished(id, creator, "ha", when, 12_000, Set.of("a", "b")); // a repeated delivery

        assertThat(db.sql("SELECT count(*) FROM feed.candidates WHERE video_id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM feed.candidate_tags WHERE video_id = :id").param("id", id).query(Long.class).single()).isEqualTo(2);
        assertThat(db.sql("SELECT likes FROM feed.candidate_stats WHERE video_id = :id").param("id", id).query(Long.class).single())
                .as("a repeat must not reset counters that engagement events already updated").isEqualTo(5);
    }

    @Test
    void removingACandidateRemovesItsStatsAndTagsAndIsRepeatable() {
        UUID id = UUID.randomUUID();
        indexer.videoPublished(id, UUID.randomUUID(), "yo", Instant.now(), 9_000, Set.of("x"));
        indexer.videoRemoved(id);
        indexer.videoRemoved(id); // repeat is a no-op
        assertThat(db.sql("SELECT (SELECT count(*) FROM feed.candidate_stats WHERE video_id = :id) "
                + "+ (SELECT count(*) FROM feed.candidate_tags WHERE video_id = :id)").param("id", id)
                .query(Long.class).single()).isZero();
    }

    @Test
    void negativeCountersAreClampedToZero() {
        UUID id = UUID.randomUUID();
        indexer.videoPublished(id, UUID.randomUUID(), "yo", Instant.now(), 9_000, Set.of());
        indexer.statsChanged(id, -5, -1, 3);
        assertThat(db.sql("SELECT likes + comments FROM feed.candidate_stats WHERE video_id = :id").param("id", id)
                .query(Long.class).single()).isZero();
    }

    @Test
    void anOlderCounterEventArrivingLateNeverOverwritesANewerOne() {
        UUID id = UUID.randomUUID();
        indexer.videoPublished(id, UUID.randomUUID(), "yo", Instant.now(), 9_000, Set.of());
        Instant earlier = Instant.parse("2026-10-22T10:00:00Z");
        Instant later = earlier.plusSeconds(5);

        indexer.statsChanged(id, 50, 5, 500, later);    // the newer one is processed first...
        indexer.statsChanged(id, 10, 1, 100, earlier);  // ...then the older one is delivered late

        assertThat(db.sql("SELECT likes FROM feed.candidate_stats WHERE video_id = :id").param("id", id)
                .query(Long.class).single()).as("the newer value wins").isEqualTo(50);
        indexer.statsChanged(id, 50, 5, 500, later); // a repeat of the same event changes nothing
        indexer.statsChanged(id, 70, 7, 700, later.plusSeconds(1));
        assertThat(db.sql("SELECT likes FROM feed.candidate_stats WHERE video_id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(70);
    }

    @Test
    void likesOfVideosThatAreNotCandidatesAreIgnoredAndLikesAreRemovedWithTheirVideo() {
        UUID viewer = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        indexer.liked(viewer, stranger); // not a candidate: nothing to remember, and no error
        assertThat(db.sql("SELECT count(*) FROM feed.user_likes").query(Long.class).single()).isZero();

        UUID id = UUID.randomUUID();
        indexer.videoPublished(id, UUID.randomUUID(), "yo", Instant.now(), 9_000, Set.of("x"));
        indexer.liked(viewer, id);
        indexer.liked(viewer, id); // repeat
        assertThat(db.sql("SELECT count(*) FROM feed.user_likes").query(Long.class).single()).isEqualTo(1);
        indexer.videoRemoved(id);
        assertThat(db.sql("SELECT count(*) FROM feed.user_likes").query(Long.class).single()).as("goes with the video").isZero();
    }

    @Test
    void followsAreIdempotentAndYouCannotFollowYourself() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        indexer.followed(a, b);
        indexer.followed(a, b);
        indexer.followed(a, a);
        assertThat(db.sql("SELECT count(*) FROM feed.following").query(Long.class).single()).isEqualTo(1);
        indexer.unfollowed(a, b);
        indexer.unfollowed(a, b);
        assertThat(db.sql("SELECT count(*) FROM feed.following").query(Long.class).single()).isZero();
    }

    @Test
    void interestsAreReplacedNotAccumulated() {
        UUID user = UUID.randomUUID();
        indexer.interestsChanged(user, Set.of("a", "b"));
        indexer.interestsChanged(user, Set.of("c"));
        assertThat(db.sql("SELECT tag FROM feed.user_interests WHERE user_id = :u").param("u", user)
                .query(String.class).list()).containsExactly("c");
    }
}
