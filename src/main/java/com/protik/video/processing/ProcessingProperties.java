package com.protik.video.processing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.scan.*} and {@code protik.ffmpeg.*} (ADR 0022), bound as one record with a prefix of {@code protik}. */
@ConfigurationProperties("protik")
public record ProcessingProperties(
        @DefaultValue Scan scan,
        @DefaultValue Ffmpeg ffmpeg) {

    /**
     * @param mode {@code clamd} (default; with no scanner reachable nothing is published) or {@code disabled}
     *             (development only)
     */
    public record Scan(@DefaultValue("clamd") String mode, @DefaultValue("localhost") String host,
            @DefaultValue("3310") int port, @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("120s") Duration readTimeout) {

        public Scan {
            if (!mode.equals("clamd") && !mode.equals("disabled")) {
                throw new IllegalArgumentException("protik.scan.mode must be clamd or disabled");
            }
        }
    }

    /**
     * @param path        the ffmpeg executable
     * @param probePath    the ffprobe executable
     * @param timeout      the longest one tool run may take before it is killed
     * @param threads      threads one encode may use
     * @param maxOutputBytes the largest file one encode may write
     */
    public record Ffmpeg(@DefaultValue("ffmpeg") String path, @DefaultValue("ffprobe") String probePath,
            @DefaultValue("5m") Duration timeout, @DefaultValue("2") int threads,
            @DefaultValue("100000000") long maxOutputBytes) {

        public Ffmpeg {
            if (threads < 1 || maxOutputBytes < 1024 || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("protik.ffmpeg limits must be positive");
            }
        }
    }
}
