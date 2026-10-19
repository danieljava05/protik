package com.protik.identity.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.session.*} (ADR 0012). */
@ConfigurationProperties("protik.session")
public record SessionProperties(
        /* How long one refresh token is valid; each use issues a new one (sliding). */
        @DefaultValue("30d") Duration refreshTokenTtl,
        /* Absolute lifetime of a session, after which the user logs in again. */
        @DefaultValue("90d") Duration maxAge,
        /*
         * A rotated token presented again within this time is rejected without revoking the
         * session (duplicate or racing requests). Later reuse revokes it. 0 disables the grace.
         */
        @DefaultValue("10s") Duration reuseGrace,
        /* How long revoked or expired sessions are kept before the purge deletes them. */
        @DefaultValue("30d") Duration purgeRetention,
        @DefaultValue("1h") Duration purgeInterval) {

    public SessionProperties {
        if (refreshTokenTtl.isZero() || refreshTokenTtl.isNegative() || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("protik.session durations must be positive");
        }
        if (reuseGrace.isNegative()) {
            throw new IllegalArgumentException("protik.session.reuse-grace must not be negative");
        }
    }
}
