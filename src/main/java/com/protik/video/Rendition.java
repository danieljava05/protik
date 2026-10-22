package com.protik.video;

/**
 * One quality level of the bitrate ladder, as published by the video module.
 *
 * @param code           for example {@code 144p} or {@code audio}
 * @param heightPx       picture height, or null for audio only
 * @param maxBitrateKbps the most the encoder may spend per second at this level (an upper bound, not a measurement)
 */
public record Rendition(String code, Integer heightPx, int maxBitrateKbps) {

    /** Upper bound of the bytes needed to play {@code durationMs} at this level. */
    public long estimateBytes(long durationMs) {
        return (long) maxBitrateKbps * 125L * durationMs / 1000L; // kbit/s * 1000 / 8 = bytes/s
    }
}
