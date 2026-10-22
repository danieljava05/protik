package com.protik.video;

import java.util.Optional;
import java.util.UUID;

/**
 * The video module's answer to "does this video exist?" for modules that must check before acting on one
 * (likes, comments, reports). It returns a yes/no with two facts, never rows or entities, so the video
 * schema stays private (ADR 0016).
 */
public interface VideoCatalog {

    /** @return the video, or empty if there is none with this ID */
    Optional<VideoInfo> find(UUID videoId);

    /** Convenience for the common check: the video exists and people can see it. */
    default boolean isPublished(UUID videoId) {
        return find(videoId).map(VideoInfo::published).orElse(false);
    }
}
