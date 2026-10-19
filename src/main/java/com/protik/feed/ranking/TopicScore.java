package com.protik.feed.ranking;

import com.protik.feed.Candidate;
import com.protik.feed.RankingContext;
import com.protik.feed.ScoreFunction;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The share of the video's tags that the viewer is interested in:
 * {@code |video tags ∩ interests| / |video tags|}. 0 when the video has no tags.
 */
@Component
@Order(4)
public class TopicScore implements ScoreFunction {

    public static final String CODE = "TOPIC";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public double score(RankingContext context, Candidate candidate) {
        if (candidate.tags().isEmpty() || context.interestTags().isEmpty()) {
            return 0.0;
        }
        long shared = candidate.tags().stream().filter(context.interestTags()::contains).count();
        return (double) shared / candidate.tags().size();
    }
}
