package com.protik.feed.events;

import com.protik.feed.service.FeedIndexer;
import com.protik.video.VideoPublished;
import com.protik.video.VideoRemoved;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the feed's candidate pool in step with the video module. {@code @ApplicationModuleListener}
 * runs after the publisher's transaction commits, in its own transaction, and the event stays in the
 * persistent registry until this method returns normally (ADR 0015). Both handlers are idempotent.
 */
@Component
class VideoEvents {

    private final FeedIndexer indexer;

    VideoEvents(FeedIndexer indexer) {
        this.indexer = indexer;
    }

    @ApplicationModuleListener
    void on(VideoPublished event) {
        indexer.videoPublished(event.videoId(), event.creatorId(), event.language(), event.publishedAt(),
                event.durationMs(), event.tags() == null ? java.util.Set.of() : event.tags());
    }

    @ApplicationModuleListener
    void on(VideoRemoved event) {
        indexer.videoRemoved(event.videoId());
    }
}
