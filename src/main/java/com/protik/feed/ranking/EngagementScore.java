package com.protik.feed.ranking;

import com.protik.feed.Candidate;
import com.protik.feed.RankingContext;
import com.protik.feed.ScoreFunction;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * How much interaction a video gets for its age. With {@code points = likes*a + comments*b + views*c}
 * and {@code rate = points per hour of age} (age counted as at least one hour, so a seconds-old video
 * cannot look infinitely popular), the value is {@code rate / (rate + k)}: 0 with no interaction,
 * 0.5 at {@code k} points per hour, approaching 1 but never reaching it.
 */
@Component
@Order(2)
public class EngagementScore implements ScoreFunction {

    public static final String CODE = "ENGAGEMENT";

    private final double halfSaturation;
    private final double likePoints;
    private final double commentPoints;
    private final double viewPoints;

    @Autowired // Spring uses this constructor; the other one exists for plain unit tests
    public EngagementScore(RankingProperties props) {
        this(props.engagementHalfSaturation(), props.likePoints(), props.commentPoints(), props.viewPoints());
    }

    public EngagementScore(double halfSaturation, double likePoints, double commentPoints, double viewPoints) {
        this.halfSaturation = halfSaturation;
        this.likePoints = likePoints;
        this.commentPoints = commentPoints;
        this.viewPoints = viewPoints;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public double score(RankingContext context, Candidate candidate) {
        double ageHours = Math.max(1.0,
                Duration.between(candidate.publishedAt(), context.asOf()).toMillis() / 3_600_000.0);
        double points = candidate.likes() * likePoints + candidate.comments() * commentPoints
                + candidate.views() * viewPoints;
        double rate = points / ageHours;
        return rate / (rate + halfSaturation);
    }
}
