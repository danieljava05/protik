package com.protik.engagement.events;

import com.protik.engagement.persistence.EngagementRepository;
import com.protik.social.CommentCountChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A comment was added or removed: mark the video so the refresher recounts it (ADR 0017 decision 6). Marking is a
 * set insert, so a repeated event is harmless.
 */
@Component
class CommentEvents {

    private final EngagementRepository repository;

    CommentEvents(EngagementRepository repository) {
        this.repository = repository;
    }

    @ApplicationModuleListener
    void on(CommentCountChanged event) {
        repository.markDirty(event.videoId());
    }
}
