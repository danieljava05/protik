package com.protik.feed.service;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a viewer stopped: the moment the session started, a fingerprint of their ranking settings,
 * and the sort key of the last item they were given (ADR 0015). Opaque to clients.
 *
 * <p>Times are whole microseconds (PostgreSQL's precision) and the score is its exact bit pattern, so
 * "what comes after this cursor" is decided without rounding. The cursor is not signed: forging one
 * only changes where the forger's own feed resumes, and every field is validated on the way in.
 */
record FeedCursor(Instant asOf, String settingsHash, double score, Instant publishedAt, UUID videoId) {

    private static final int MAX_LENGTH = 300;
    private static final String VERSION = "v1";

    String encode() {
        String text = String.join(".", VERSION, Long.toString(micros(asOf)), settingsHash,
                Long.toHexString(Double.doubleToLongBits(score)), Long.toString(micros(publishedAt)), videoId.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static FeedCursor decode(String cursor) {
        try {
            if (cursor.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("too long");
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\.", -1);
            if (parts.length != 6 || !VERSION.equals(parts[0]) || !parts[2].matches("[0-9a-f]{16}")) {
                throw new IllegalArgumentException("shape");
            }
            double score = Double.longBitsToDouble(Long.parseUnsignedLong(parts[3], 16));
            if (Double.isNaN(score) || score < 0) {
                throw new IllegalArgumentException("score");
            }
            return new FeedCursor(fromMicros(Long.parseLong(parts[1])), parts[2], score,
                    fromMicros(Long.parseLong(parts[4])), UUID.fromString(parts[5]));
        } catch (RuntimeException e) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "The cursor is not valid.");
        }
    }

    /** The truncation every session start uses, so a cursor's time equals the one the query used. */
    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    private static Instant fromMicros(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }
}
