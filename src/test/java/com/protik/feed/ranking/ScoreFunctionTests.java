package com.protik.feed.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.protik.feed.Candidate;
import com.protik.feed.RankingContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The formulas of ADR 0014, one component at a time. */
class ScoreFunctionTests {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();

    private static RankingContext context(Set<UUID> followed, Set<String> interests) {
        return new RankingContext(VIEWER, NOW, Map.of(), false, followed, interests);
    }

    private static RankingContext context() {
        return context(Set.of(), Set.of());
    }

    private static Candidate video(Duration age, long likes, long comments, long views, Set<String> tags) {
        return new Candidate(UUID.randomUUID(), CREATOR, NOW.minus(age), likes, comments, views, tags);
    }

    // ---- RECENCY ----

    @Test
    void recencyIsOneWhenNewAndHalvesEveryHalfLife() {
        var recency = new RecencyScore(Duration.ofHours(24));
        assertThat(recency.score(context(), video(Duration.ZERO, 0, 0, 0, Set.of()))).isEqualTo(1.0);
        assertThat(recency.score(context(), video(Duration.ofHours(24), 0, 0, 0, Set.of()))).isCloseTo(0.5, within(1e-12));
        assertThat(recency.score(context(), video(Duration.ofHours(48), 0, 0, 0, Set.of()))).isCloseTo(0.25, within(1e-12));
        assertThat(recency.score(context(), video(Duration.ofHours(12), 0, 0, 0, Set.of()))).isCloseTo(Math.sqrt(0.5), within(1e-12));
    }

    @Test
    void recencyOnlyEverDecreasesAndStaysInRange() {
        var recency = new RecencyScore(Duration.ofHours(24));
        double previous = 2;
        for (int hours = 0; hours <= 24 * 365; hours += 7) {
            double value = recency.score(context(), video(Duration.ofHours(hours), 0, 0, 0, Set.of()));
            assertThat(value).isBetween(0.0, 1.0).isLessThanOrEqualTo(previous);
            previous = value;
        }
    }

    @Test
    void aVideoDatedInTheFutureCountsAsNewNotBetterThanNew() {
        var recency = new RecencyScore(Duration.ofHours(24));
        assertThat(recency.score(context(), video(Duration.ofHours(-5), 0, 0, 0, Set.of()))).isEqualTo(1.0);
    }

    @Test
    void theHalfLifeIsConfigurable() {
        var quick = new RecencyScore(Duration.ofHours(1));
        assertThat(quick.score(context(), video(Duration.ofHours(1), 0, 0, 0, Set.of()))).isCloseTo(0.5, within(1e-12));
    }

    // ---- ENGAGEMENT ----

    private final EngagementScore engagement = new EngagementScore(10, 1, 2, 0.1);

    @Test
    void noInteractionScoresZero() {
        assertThat(engagement.score(context(), video(Duration.ofHours(3), 0, 0, 0, Set.of()))).isZero();
    }

    @Test
    void tenPointsAnHourScoresHalf() {
        // 10 likes in the first hour (age counts as at least 1 hour)
        assertThat(engagement.score(context(), video(Duration.ofMinutes(10), 10, 0, 0, Set.of())))
                .isCloseTo(0.5, within(1e-12));
        // 40 likes over 4 hours is the same rate
        assertThat(engagement.score(context(), video(Duration.ofHours(4), 40, 0, 0, Set.of())))
                .isCloseTo(0.5, within(1e-12));
    }

    @Test
    void commentsCountMoreThanLikesAndLikesMoreThanViews() {
        var age = Duration.ofHours(5);
        double comments = engagement.score(context(), video(age, 0, 10, 0, Set.of()));
        double likes = engagement.score(context(), video(age, 10, 0, 0, Set.of()));
        double views = engagement.score(context(), video(age, 0, 0, 10, Set.of()));
        assertThat(comments).isGreaterThan(likes);
        assertThat(likes).isGreaterThan(views);
    }

    @Test
    void engagementGrowsWithInteractionShrinksWithAgeAndNeverReachesOne() {
        double previous = -1;
        for (long likes = 0; likes <= 10_000_000; likes = likes * 4 + 1) {
            double value = engagement.score(context(), video(Duration.ofHours(2), likes, likes, likes, Set.of()));
            assertThat(value).isGreaterThan(previous).isLessThan(1.0);
            previous = value;
        }
        double young = engagement.score(context(), video(Duration.ofHours(2), 100, 10, 1000, Set.of()));
        double old = engagement.score(context(), video(Duration.ofHours(200), 100, 10, 1000, Set.of()));
        assertThat(young).isGreaterThan(old);
    }

    @Test
    void aSecondsOldVideoCannotLookInfinitelyPopular() {
        // without the one-hour floor, 5 likes after 1 second would be 18,000 points an hour
        double fresh = engagement.score(context(), video(Duration.ofSeconds(1), 5, 0, 0, Set.of()));
        assertThat(fresh).isCloseTo(5.0 / 15.0, within(1e-12));
    }

    // ---- FOLLOWING ----

    @Test
    void followingIsOneForFollowedCreatorsAndZeroOtherwise() {
        var following = new FollowingScore();
        assertThat(following.score(context(Set.of(CREATOR), Set.of()), video(Duration.ZERO, 0, 0, 0, Set.of()))).isEqualTo(1.0);
        assertThat(following.score(context(Set.of(UUID.randomUUID()), Set.of()), video(Duration.ZERO, 0, 0, 0, Set.of()))).isZero();
        assertThat(following.score(context(), video(Duration.ZERO, 0, 0, 0, Set.of()))).isZero();
    }

    // ---- TOPIC ----

    @Test
    void topicIsTheShareOfTheVideosTagsTheViewerLikes() {
        var topic = new TopicScore();
        var interests = context(Set.of(), Set.of("football", "afrobeats", "comedy"));
        assertThat(topic.score(interests, video(Duration.ZERO, 0, 0, 0, Set.of("football", "comedy", "news", "food"))))
                .isEqualTo(0.5);
        assertThat(topic.score(interests, video(Duration.ZERO, 0, 0, 0, Set.of("football")))).isEqualTo(1.0);
        assertThat(topic.score(interests, video(Duration.ZERO, 0, 0, 0, Set.of("news")))).isZero();
    }

    @Test
    void topicIsZeroWhenThereIsNothingToCompare() {
        var topic = new TopicScore();
        assertThat(topic.score(context(Set.of(), Set.of("football")), video(Duration.ZERO, 0, 0, 0, Set.of()))).isZero();
        assertThat(topic.score(context(Set.of(), Set.of()), video(Duration.ZERO, 0, 0, 0, Set.of("football")))).isZero();
    }

    // ---- inputs ----

    @Test
    void invalidInputsAreRejectedAtTheBoundary() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> video(Duration.ZERO, -1, 0, 0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RankingContext(VIEWER, NOW,
                Map.of("RECENCY", 1.5), false, Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RankingContext(VIEWER, NOW,
                Map.of("RECENCY", -0.1), false, Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RankingContext(VIEWER, NOW,
                Map.of("RECENCY", Double.NaN), false, Set.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
