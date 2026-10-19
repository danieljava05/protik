package com.protik.feed.ranking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Constants of the default scoring components under {@code protik.ranking.*} (ADR 0014).
 * These are starting points; nothing has been tuned against real viewing behaviour.
 */
@ConfigurationProperties("protik.ranking")
public record RankingProperties(
        /* RECENCY: a video's recency halves every half-life. */
        @DefaultValue("24h") Duration recencyHalfLife,
        /* ENGAGEMENT: interaction points per hour at which the component reaches 0.5. */
        @DefaultValue("10") double engagementHalfSaturation,
        /* ENGAGEMENT: how many points each kind of interaction is worth. */
        @DefaultValue("1") double likePoints,
        @DefaultValue("2") double commentPoints,
        @DefaultValue("0.1") double viewPoints) {

    public RankingProperties {
        if (recencyHalfLife.isZero() || recencyHalfLife.isNegative()) {
            throw new IllegalArgumentException("protik.ranking.recency-half-life must be positive");
        }
        if (engagementHalfSaturation <= 0 || likePoints < 0 || commentPoints < 0 || viewPoints < 0) {
            throw new IllegalArgumentException("protik.ranking engagement constants must be positive");
        }
    }
}
