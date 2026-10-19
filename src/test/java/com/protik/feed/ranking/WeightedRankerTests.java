package com.protik.feed.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.protik.feed.Candidate;
import com.protik.feed.ComponentScore;
import com.protik.feed.RankedItem;
import com.protik.feed.RankingContext;
import com.protik.feed.ScoreFunction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR 0014: the score is the sum of the explanation, ordering is deterministic, components plug in. */
class WeightedRankerTests {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID VIEWER = UUID.randomUUID();

    private final WeightedRanker ranker = new WeightedRanker(List.of(
            new RecencyScore(Duration.ofHours(24)), new EngagementScore(10, 1, 2, 0.1),
            new FollowingScore(), new TopicScore()));

    private static final Map<String, Double> BALANCED = Map.of(
            "RECENCY", 0.3, "ENGAGEMENT", 0.3, "FOLLOWING", 0.2, "TOPIC", 0.2);

    private static RankingContext context(Map<String, Double> weights, boolean followedOnly, Set<UUID> followed,
            Set<String> interests) {
        return new RankingContext(VIEWER, NOW, weights, followedOnly, followed, interests);
    }

    private static Candidate video(UUID id, UUID creator, Duration age, long likes, Set<String> tags) {
        return new Candidate(id, creator, NOW.minus(age), likes, 0, 0, tags);
    }

    private static Candidate video(Duration age, long likes) {
        return video(UUID.randomUUID(), UUID.randomUUID(), age, likes, Set.of());
    }

    // ---- the explanation is the score ----

    @Test
    void theScoreEqualsTheSumOfTheListedContributionsForThousandsOfRandomInputs() {
        var random = new Random(42);
        for (int round = 0; round < 2000; round++) {
            var weights = Map.of("RECENCY", random.nextDouble(), "ENGAGEMENT", random.nextDouble(),
                    "FOLLOWING", random.nextDouble(), "TOPIC", random.nextDouble());
            UUID followedCreator = UUID.randomUUID();
            var ctx = context(weights, false, Set.of(followedCreator), Set.of("a", "b", "c"));
            var candidates = new ArrayList<Candidate>();
            for (int i = 0; i < 20; i++) {
                candidates.add(new Candidate(UUID.randomUUID(), random.nextBoolean() ? followedCreator : UUID.randomUUID(),
                        NOW.minusSeconds(random.nextInt(60 * 60 * 24 * 90)), random.nextInt(100_000),
                        random.nextInt(10_000), random.nextInt(1_000_000),
                        random.nextBoolean() ? Set.of("a", "x") : Set.of("b", "c", "d")));
            }
            for (RankedItem item : ranker.rank(ctx, candidates)) {
                double sum = item.components().stream().mapToDouble(ComponentScore::contribution).sum();
                assertThat(item.score()).isCloseTo(sum, within(1e-12));
                for (ComponentScore c : item.components()) {
                    assertThat(c.value()).isBetween(0.0, 1.0);
                    assertThat(c.weight()).isBetween(0.0, 1.0);
                    assertThat(c.contribution()).isCloseTo(c.value() * c.weight(), within(1e-15));
                }
                assertThat(item.score()).isBetween(0.0, weights.values().stream().mapToDouble(d -> d).sum() + 1e-12);
            }
        }
    }

    @Test
    void everyItemExplainsEveryComponentInAStableOrderEvenAtWeightZero() {
        var ctx = context(Map.of("RECENCY", 1.0), false, Set.of(), Set.of());
        var items = ranker.rank(ctx, List.of(video(Duration.ofHours(1), 5), video(Duration.ofHours(2), 5)));
        for (RankedItem item : items) {
            assertThat(item.components()).extracting(ComponentScore::code)
                    .containsExactly("RECENCY", "ENGAGEMENT", "FOLLOWING", "TOPIC");
            assertThat(item.components().get(1).weight()).as("unweighted components are listed with weight 0").isZero();
        }
    }

    // ---- ordering ----

    @Test
    void itemsComeBestFirstAndInputOrderDoesNotMatter() {
        var ctx = context(BALANCED, false, Set.of(), Set.of());
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < 50; i++) {
            candidates.add(video(Duration.ofHours(i), (i * 37) % 11 * 100));
        }
        List<UUID> expected = ranker.rank(ctx, candidates).stream().map(RankedItem::videoId).toList();
        for (int shuffle = 0; shuffle < 20; shuffle++) {
            Collections.shuffle(candidates, new Random(shuffle));
            assertThat(ranker.rank(ctx, candidates).stream().map(RankedItem::videoId).toList()).isEqualTo(expected);
        }
        double previous = Double.MAX_VALUE;
        for (RankedItem item : ranker.rank(ctx, candidates)) {
            assertThat(item.score()).isLessThanOrEqualTo(previous);
            previous = item.score();
        }
    }

    @Test
    void ties_breakByNewestThenByVideoId() {
        var ctx = context(Map.of("FOLLOWING", 1.0), false, Set.of(), Set.of()); // every score is 0
        UUID low = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID high = UUID.fromString("00000000-0000-7000-8000-000000000002");
        var older = video(low, UUID.randomUUID(), Duration.ofHours(5), 0, Set.of());
        var newerLow = video(low, UUID.randomUUID(), Duration.ofHours(1), 0, Set.of());
        var newerHigh = video(high, UUID.randomUUID(), Duration.ofHours(1), 0, Set.of());
        var order = ranker.rank(ctx, List.of(older, newerLow, newerHigh));
        assertThat(order).extracting(RankedItem::videoId).containsExactly(high, low, low);
        assertThat(order.get(2).videoId()).isEqualTo(older.videoId());
    }

    @Test
    void withAllWeightsZeroTheFeedIsSimplyNewestFirst() {
        var ctx = context(Map.of(), false, Set.of(), Set.of());
        var candidates = List.of(video(Duration.ofHours(30), 999), video(Duration.ofHours(1), 0), video(Duration.ofHours(10), 5));
        var order = ranker.rank(ctx, candidates);
        assertThat(order).allMatch(i -> i.score() == 0.0);
        assertThat(order).extracting(RankedItem::videoId).containsExactly(
                candidates.get(1).videoId(), candidates.get(2).videoId(), candidates.get(0).videoId());
    }

    @Test
    void uuidOrderIsUnsignedLikePostgresNotJavasSignedOrder() {
        UUID small = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID big = UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff"); // top bit set: negative as a signed long
        assertThat(big.compareTo(small)).as("java's own order is signed").isNegative();
        assertThat(WeightedRanker.compareUnsigned(big, small)).as("the database's order is unsigned").isPositive();
        var ctx = context(Map.of(), false, Set.of(), Set.of());
        var a = video(small, UUID.randomUUID(), Duration.ofHours(1), 0, Set.of());
        var b = video(big, UUID.randomUUID(), Duration.ofHours(1), 0, Set.of());
        assertThat(ranker.rank(ctx, List.of(a, b))).extracting(RankedItem::videoId).containsExactly(big, small);
    }

    // ---- the user's controls ----

    @Test
    void changingWeightsChangesTheOrderAsTheUserWouldExpect() {
        var fresh = video(Duration.ofMinutes(10), 0);        // new, no engagement
        var popular = video(Duration.ofHours(48), 5000);     // old, very engaging
        var all = List.of(fresh, popular);

        var recencyFirst = ranker.rank(context(Map.of("RECENCY", 1.0, "ENGAGEMENT", 0.1), false, Set.of(), Set.of()), all);
        var engagementFirst = ranker.rank(context(Map.of("RECENCY", 0.1, "ENGAGEMENT", 1.0), false, Set.of(), Set.of()), all);

        assertThat(recencyFirst.get(0).videoId()).isEqualTo(fresh.videoId());
        assertThat(engagementFirst.get(0).videoId()).isEqualTo(popular.videoId());
    }

    @Test
    void raisingAWeightNeverLowersThatComponentsContribution() {
        var c = video(Duration.ofHours(3), 120);
        double previous = -1;
        for (double w = 0; w <= 1.0; w += 0.05) {
            var item = ranker.rank(context(Map.of("ENGAGEMENT", w), false, Set.of(), Set.of()), List.of(c)).get(0);
            double contribution = item.components().get(1).contribution();
            assertThat(contribution).isGreaterThanOrEqualTo(previous);
            previous = contribution;
        }
    }

    @Test
    void followedOnlyModeShowsOnlyFollowedCreators() {
        UUID friend = UUID.randomUUID();
        var fromFriend = video(UUID.randomUUID(), friend, Duration.ofHours(2), 0, Set.of());
        var stranger = video(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(1), 9999, Set.of());
        var ctx = context(Map.of("RECENCY", 0.5, "FOLLOWING", 1.0), true, Set.of(friend), Set.of());
        assertThat(ranker.rank(ctx, List.of(stranger, fromFriend))).extracting(RankedItem::videoId)
                .containsExactly(fromFriend.videoId());
        // following nobody in that mode means an empty feed, not everyone's videos
        assertThat(ranker.rank(context(Map.of("FOLLOWING", 1.0), true, Set.of(), Set.of()), List.of(stranger))).isEmpty();
    }

    @Test
    void anEmptyCandidateListGivesAnEmptyResult() {
        assertThat(ranker.rank(context(BALANCED, false, Set.of(), Set.of()), List.of())).isEmpty();
    }

    // ---- pluggability and defensiveness ----

    @Test
    void aNewComponentPlugsInWithoutChangingTheRanker() {
        ScoreFunction shortVideos = new ScoreFunction() {
            @Override
            public String code() {
                return "SHORT";
            }

            @Override
            public double score(RankingContext context, Candidate candidate) {
                return candidate.likes() % 2 == 0 ? 1.0 : 0.0; // stand-in rule, just to prove the plug-in point
            }
        };
        var extended = new WeightedRanker(List.of(new RecencyScore(Duration.ofHours(24)), shortVideos));
        var even = video(Duration.ofHours(1), 2);
        var odd = video(Duration.ofHours(1), 3);
        var items = extended.rank(context(Map.of("SHORT", 1.0), false, Set.of(), Set.of()), List.of(odd, even));
        assertThat(items.get(0).videoId()).isEqualTo(even.videoId());
        assertThat(items.get(0).components()).extracting(ComponentScore::code).containsExactly("RECENCY", "SHORT");
    }

    @Test
    void aComponentReturningNonsenseIsAnErrorNotASilentlyWrongFeed() {
        for (double bad : new double[] {-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            var broken = new WeightedRanker(List.of(new ScoreFunction() {
                @Override
                public String code() {
                    return "BROKEN";
                }

                @Override
                public double score(RankingContext context, Candidate candidate) {
                    return bad;
                }
            }));
            assertThatThrownBy(() -> broken.rank(context(Map.of("BROKEN", 1.0), false, Set.of(), Set.of()),
                    List.of(video(Duration.ZERO, 0)))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BROKEN");
        }
    }

    @Test
    void aWeightForAnUnknownComponentIsRefused() {
        assertThatThrownBy(() -> ranker.rank(context(Map.of("VIBES", 1.0), false, Set.of(), Set.of()),
                List.of(video(Duration.ZERO, 0)))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIBES");
    }

    @Test
    void twoComponentsWithTheSameCodeAreRefusedAtStartup() {
        assertThatThrownBy(() -> new WeightedRanker(List.of(new FollowingScore(), new FollowingScore())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FOLLOWING");
    }

    // ---- measured cost ----

    @Test
    void rankingALargePoolIsFastEnoughForTheFeedHotPath() {
        var random = new Random(7);
        var ctx = context(BALANCED, false, Set.of(UUID.randomUUID()), Set.of("a", "b"));
        for (int size : new int[] {500, 10_000, 100_000}) {
            var candidates = new ArrayList<Candidate>(size);
            for (int i = 0; i < size; i++) {
                candidates.add(new Candidate(UUID.randomUUID(), UUID.randomUUID(),
                        NOW.minusSeconds(random.nextInt(7 * 24 * 3600)), random.nextInt(5000), random.nextInt(500),
                        random.nextInt(50_000), new HashSet<>(List.of("a", "z" + random.nextInt(50)))));
            }
            ranker.rank(ctx, candidates); // warm up the JIT
            long start = System.nanoTime();
            ranker.rank(ctx, candidates);
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("RANKER-MEASUREMENT pool=%d took=%dms%n", size, millis);
            assertThat(millis).as("pool of %d", size).isLessThan(5_000); // a loose guard, not a benchmark
        }
    }
}
