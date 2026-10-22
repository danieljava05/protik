package com.protik.engagement.service;

import com.protik.engagement.VideoLiked;
import com.protik.engagement.VideoUnliked;
import com.protik.engagement.persistence.EngagementRepository;
import com.protik.engagement.persistence.EngagementRepository.Counters;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.video.VideoCatalog;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Liking and unliking (ADR 0016). Both are idempotent; events and counter work happen only on a real change. */
@Service
public class LikeService {

    public record Engagement(long likes, long comments, long views, boolean likedByViewer) {
    }

    private final EngagementRepository repository;
    private final VideoCatalog videos;
    private final ApplicationEventPublisher events;

    LikeService(EngagementRepository repository, VideoCatalog videos, ApplicationEventPublisher events) {
        this.repository = repository;
        this.videos = videos;
        this.events = events;
    }

    /** Only published videos can be liked; anything else looks like it does not exist. */
    @Transactional
    public void like(UUID userId, UUID videoId) {
        if (!videos.isPublished(videoId)) {
            throw notFound();
        }
        if (repository.insertLike(userId, videoId)) {
            repository.markDirty(videoId);
            events.publishEvent(new VideoLiked(videoId, userId));
        }
    }

    /** A like can be withdrawn even from a video that has since been taken down. */
    @Transactional
    public void unlike(UUID userId, UUID videoId) {
        if (videos.find(videoId).isEmpty()) {
            throw notFound();
        }
        if (repository.deleteLike(userId, videoId)) {
            repository.markDirty(videoId);
            events.publishEvent(new VideoUnliked(videoId, userId));
        }
    }

    /** The counters (about a second behind) and the caller's own like (always current). */
    @Transactional(readOnly = true)
    public Engagement engagement(UUID userId, UUID videoId) {
        if (videos.find(videoId).isEmpty()) {
            throw notFound();
        }
        Counters counters = repository.counters(videoId);
        return new Engagement(counters.likes(), counters.comments(), counters.views(),
                repository.isLiked(userId, videoId));
    }

    private static ApiException notFound() {
        return new ApiException(ProblemType.NOT_FOUND, "Video not found.");
    }
}
