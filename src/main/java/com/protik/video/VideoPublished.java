package com.protik.video;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A video became watchable. Published by the video module in the same transaction that publishes
 * it; other modules (the feed) react to it. Part of the video module's public API, so changes to it
 * are changes to a contract: add fields, do not repurpose them.
 *
 * <p>Events are delivered at least once, so listeners must be idempotent.
 *
 * @param language ISO code of the spoken language ({@code yo}, {@code ig}, {@code ha}, {@code pcm},
 *                 {@code en}), or null if unknown
 * @param tags     the video's hashtags, in any case and with or without a leading '#'
 */
public record VideoPublished(UUID videoId, UUID creatorId, Instant publishedAt, int durationMs, String language,
        Set<String> tags) {
}
