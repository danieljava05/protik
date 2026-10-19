package com.protik.feed.ranking;

import com.protik.feed.Candidate;
import com.protik.feed.RankingContext;
import com.protik.feed.ScoreFunction;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** {@code 0.5 ^ (age / half-life)}: 1 for a brand-new video, 0.5 after one half-life, and so on. */
@Component
@Order(1)
public class RecencyScore implements ScoreFunction {

    public static final String CODE = "RECENCY";

    private final double halfLifeSeconds;

    @Autowired // Spring uses this constructor; the other one exists for plain unit tests
    public RecencyScore(RankingProperties props) {
        this(props.recencyHalfLife());
    }

    public RecencyScore(Duration halfLife) {
        this.halfLifeSeconds = halfLife.toMillis() / 1000.0;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public double score(RankingContext context, Candidate candidate) {
        double ageSeconds = Duration.between(candidate.publishedAt(), context.asOf()).toMillis() / 1000.0;
        // A video dated in the future (clock skew) counts as brand new, never as more than 1.
        return Math.pow(0.5, Math.max(0, ageSeconds) / halfLifeSeconds);
    }
}
