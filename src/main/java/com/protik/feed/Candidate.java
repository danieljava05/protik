package com.protik.feed;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A video the feed may show, with the facts the scoring functions need. This is the feed's own
 * read model of a video (ADR 0005 decision 4): the feed never reads the video or engagement
 * tables directly.
 *
 * @param likes    non-negative counters, eventually consistent with the engagement module
 * @param tags     normalised hashtags of the video (may be empty)
 */
public record Candidate(UUID videoId, UUID creatorId, Instant publishedAt, long likes, long comments, long views,
        Set<String> tags) {

    public Candidate {
        if (likes < 0 || comments < 0 || views < 0) {
            throw new IllegalArgumentException("counters must not be negative");
        }
        tags = Set.copyOf(tags);
    }
}
