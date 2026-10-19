package com.protik.feed;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything about the viewer and the moment that scoring may use. Scoring is a pure function
 * of this and a {@link Candidate}, so the same inputs always give the same ranking.
 *
 * @param asOf             the moment ages are measured from; fixed per feed session so pages stay stable
 * @param weights          weight per component code, each in [0, 1] (the user's mode plus their overrides)
 * @param followedOnly     the viewer's mode shows only creators they follow
 * @param followedCreators creators the viewer follows
 * @param interestTags     hashtags the viewer engages with
 */
public record RankingContext(UUID userId, Instant asOf, Map<String, Double> weights, boolean followedOnly,
        Set<UUID> followedCreators, Set<String> interestTags) {

    public RankingContext {
        weights.forEach((code, weight) -> {
            if (weight == null || weight.isNaN() || weight < 0.0 || weight > 1.0) {
                throw new IllegalArgumentException("weight for " + code + " must be in [0, 1]");
            }
        });
        weights = Map.copyOf(weights);
        followedCreators = Set.copyOf(followedCreators);
        interestTags = Set.copyOf(interestTags);
    }
}
