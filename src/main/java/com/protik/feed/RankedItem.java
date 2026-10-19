package com.protik.feed;

import java.util.List;
import java.util.UUID;

/**
 * A candidate with its score and the full explanation of that score.
 *
 * @param score      the sum of the contributions of {@code components}, in order
 * @param components every component that was scored, including those with weight 0
 */
public record RankedItem(UUID videoId, double score, List<ComponentScore> components) {

    public RankedItem {
        components = List.copyOf(components);
    }
}
