package com.protik.feed.events;

import com.protik.engagement.VideoLiked;
import com.protik.engagement.VideoStatsChanged;
import com.protik.engagement.VideoUnliked;
import com.protik.feed.service.FeedIndexer;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the feed's copy of likes and counters current (ADR 0016). Likes feed the viewer's derived interests;
 * counters feed the ENGAGEMENT component and are applied only if newer than what is already stored.
 */
@Component
class EngagementEvents {

    private final FeedIndexer indexer;

    EngagementEvents(FeedIndexer indexer) {
        this.indexer = indexer;
    }

    @ApplicationModuleListener
    void on(VideoLiked event) {
        indexer.liked(event.userId(), event.videoId());
    }

    @ApplicationModuleListener
    void on(VideoUnliked event) {
        indexer.unliked(event.userId(), event.videoId());
    }

    @ApplicationModuleListener
    void on(VideoStatsChanged event) {
        indexer.statsChanged(event.videoId(), event.likes(), event.comments(), event.views(), event.computedAt());
    }
}
