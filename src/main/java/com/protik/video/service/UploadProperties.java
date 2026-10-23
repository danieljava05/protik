package com.protik.video.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Limits under {@code protik.upload.*} (ADR 0022). */
@ConfigurationProperties("protik.upload")
public record UploadProperties(
        /* The most bytes one upload may contain; the server stops reading beyond it. */
        @DefaultValue("200000000") long maxBytes,
        /* The longest video accepted. */
        @DefaultValue("3m") Duration maxDuration,
        /* Uploads a person may start per hour. */
        @DefaultValue("20") int perHour) {

    public UploadProperties {
        if (maxBytes < 1024 || maxDuration.isNegative() || maxDuration.isZero() || perHour < 1) {
            throw new IllegalArgumentException("protik.upload limits must be positive (and max-bytes at least 1024)");
        }
    }
}
