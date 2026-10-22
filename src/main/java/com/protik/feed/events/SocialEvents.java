package com.protik.feed.events;

import com.protik.feed.service.FeedIndexer;
import com.protik.social.UserFollowed;
import com.protik.social.UserUnfollowed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Keeps the feed's copy of the follow graph current (ADR 0016). Both handlers are idempotent. */
@Component
class SocialEvents {

    private final FeedIndexer indexer;

    SocialEvents(FeedIndexer indexer) {
        this.indexer = indexer;
    }

    @ApplicationModuleListener
    void on(UserFollowed event) {
        indexer.followed(event.userId(), event.creatorId());
    }

    @ApplicationModuleListener
    void on(UserUnfollowed event) {
        indexer.unfollowed(event.userId(), event.creatorId());
    }
}
