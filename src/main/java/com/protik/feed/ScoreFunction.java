package com.protik.feed;

/**
 * One scoring component. Implement it as a Spring bean to add a component; the ranker does not
 * change (ADR 0014). Implementations must be pure and fast: no I/O, no clock reads (use
 * {@link RankingContext#asOf()}), and the same inputs must give the same output.
 */
public interface ScoreFunction {

    /** The component's code; must match a row in {@code feed.ranking_components}. */
    String code();

    /** @return a value in [0, 1]. The ranker rejects anything else rather than hiding a bug. */
    double score(RankingContext context, Candidate candidate);
}
