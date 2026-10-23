package com.protik.engagement.service;

import com.protik.identity.DeletionAcknowledgements;
import com.protik.identity.UserDeletionRequested;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import com.protik.engagement.persistence.EngagementRepository;

/** Deletes a deleted user's likes and views, then marks the affected videos for recounting (ADR 0020). */
@Component
class EngagementAccountDeletion {

    private final JdbcClient db;
    private final EngagementRepository repository;
    private final DeletionAcknowledgements acknowledgements;

    EngagementAccountDeletion(JdbcClient db, EngagementRepository repository, DeletionAcknowledgements acknowledgements) {
        this.db = db;
        this.repository = repository;
        this.acknowledgements = acknowledgements;
    }

    @ApplicationModuleListener
    void on(UserDeletionRequested event) {
        List<UUID> liked = db.sql("DELETE FROM engagement.video_likes WHERE user_id = :u RETURNING video_id")
                .param("u", event.userId()).query(UUID.class).list();
        List<UUID> viewed = db.sql("DELETE FROM engagement.view_events WHERE user_id = :u RETURNING video_id")
                .param("u", event.userId()).query(UUID.class).list();
        liked.forEach(repository::markDirty);
        viewed.forEach(repository::markDirty);
        acknowledgements.acknowledge(event.requestId(), "engagement");
    }
}
