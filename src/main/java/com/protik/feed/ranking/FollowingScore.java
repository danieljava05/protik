package com.protik.feed.ranking;

import com.protik.feed.Candidate;
import com.protik.feed.RankingContext;
import com.protik.feed.ScoreFunction;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 1 if the viewer follows the creator, otherwise 0. */
@Component
@Order(3)
public class FollowingScore implements ScoreFunction {

    public static final String CODE = "FOLLOWING";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public double score(RankingContext context, Candidate candidate) {
        return context.followedCreators().contains(candidate.creatorId()) ? 1.0 : 0.0;
    }
}
