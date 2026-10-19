package com.protik.feed;

import java.util.Collection;
import java.util.List;

/**
 * Orders candidates for one viewer and says why. The feed depends only on this interface, so a
 * different ranking strategy can replace the default without touching the feed (ADR 0014).
 */
public interface Ranker {

    /**
     * @return the candidates the viewer may see, best first, each with its explanation. The order
     *         is deterministic: score, then newest, then video ID. Input order does not matter.
     */
    List<RankedItem> rank(RankingContext context, Collection<Candidate> candidates);
}
