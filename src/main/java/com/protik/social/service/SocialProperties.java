package com.protik.social.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.social.*} (ADR 0017). */
@ConfigurationProperties("protik.social")
public record SocialProperties(
        /* How many comments one user may post per window. */
        @DefaultValue("20") int commentsPerWindow,
        @DefaultValue("1m") Duration commentWindow,
        @DefaultValue("20") int defaultPageSize,
        @DefaultValue("50") int maxPageSize) {

    public SocialProperties {
        if (commentsPerWindow < 1 || defaultPageSize < 1 || maxPageSize < defaultPageSize) {
            throw new IllegalArgumentException("protik.social limits must be positive and maxPageSize >= defaultPageSize");
        }
    }
}
