package com.protik.video.internal;

import com.protik.identity.DeletionAcknowledgements;
import com.protik.identity.UserDeletionRequested;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import com.protik.video.VideoRemoved;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Deletes a deleted user's videos for real (ADR 0020). {@link VideoRemoved} tells the feed to drop each one. Stored
 * files are not deleted yet because uploads do not exist yet (T1.3); that ticket must queue their keys here.
 */
@Component
class VideoAccountDeletion {

    private final JdbcClient db;
    private final ApplicationEventPublisher events;
    private final DeletionAcknowledgements acknowledgements;

    VideoAccountDeletion(JdbcClient db, ApplicationEventPublisher events, DeletionAcknowledgements acknowledgements) {
        this.db = db;
        this.events = events;
        this.acknowledgements = acknowledgements;
    }

    @ApplicationModuleListener
    void on(UserDeletionRequested event) {
        List<UUID> removed = db.sql("DELETE FROM video.videos WHERE creator_id = :u RETURNING id")
                .param("u", event.userId()).query(UUID.class).list();
        removed.forEach(id -> events.publishEvent(new VideoRemoved(id)));
        acknowledgements.acknowledge(event.requestId(), "video");
    }
}
