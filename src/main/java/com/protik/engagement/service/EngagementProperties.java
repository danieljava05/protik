package com.protik.engagement.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.engagement.*} (ADR 0016). */
@ConfigurationProperties("protik.engagement")
public record EngagementProperties(
        /* How many dirty videos one refresh recounts. */
        @DefaultValue("100") int refreshBatch) {

    public EngagementProperties {
        if (refreshBatch < 1) {
            throw new IllegalArgumentException("protik.engagement.refresh-batch must be at least 1");
        }
    }
}
