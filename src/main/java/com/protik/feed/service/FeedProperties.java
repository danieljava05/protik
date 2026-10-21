package com.protik.feed.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.feed.*} (ADR 0015). */
@ConfigurationProperties("protik.feed")
public record FeedProperties(
        /* How many of the newest candidates are ranked per request. The ranker can promote only within this pool. */
        @DefaultValue("500") int poolSize,
        /* Candidates older than this are not considered. */
        @DefaultValue("14d") Duration window,
        @DefaultValue("20") int defaultLimit,
        @DefaultValue("50") int maxLimit) {

    public FeedProperties {
        if (poolSize < 1 || defaultLimit < 1 || maxLimit < defaultLimit) {
            throw new IllegalArgumentException("protik.feed sizes must be positive and maxLimit >= defaultLimit");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("protik.feed.window must be positive");
        }
    }
}
