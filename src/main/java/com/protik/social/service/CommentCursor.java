package com.protik.social.service;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a reader stopped in a comment list: the sort key (time in whole microseconds, PostgreSQL's precision,
 * and ID) of the last comment returned. Opaque to clients; every field is validated on the way in.
 */
record CommentCursor(Instant createdAt, UUID id) {

    private static final int MAX_LENGTH = 120;
    private static final String VERSION = "c1";

    String encode() {
        String text = VERSION + "." + ChronoUnit.MICROS.between(Instant.EPOCH, createdAt) + "." + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static CommentCursor decode(String cursor) {
        try {
            if (cursor.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("too long");
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("shape");
            }
            return new CommentCursor(Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS),
                    UUID.fromString(parts[2]));
        } catch (RuntimeException e) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "The cursor is not valid.");
        }
    }
}
