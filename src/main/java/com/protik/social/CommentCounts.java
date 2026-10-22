package com.protik.social;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The social module's answer to "how many visible comments does this video have?" for modules that keep
 * counters (ADR 0017). Replies count; hidden and removed comments do not.
 */
public interface CommentCounts {

    /** @return a count for every requested video, 0 for those without comments */
    Map<UUID, Long> countVisible(Collection<UUID> videoIds);

    default long countVisible(UUID videoId) {
        return countVisible(java.util.List.of(videoId)).getOrDefault(videoId, 0L);
    }
}
