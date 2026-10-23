package com.protik.social.service;

import com.protik.identity.DeletionAcknowledgements;
import com.protik.identity.UserDeletionRequested;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import com.protik.social.CommentCountChanged;
import org.springframework.context.ApplicationEventPublisher;

/** Deletes what a deleted user wrote and every follow and block they were part of (ADR 0020). */
@Component
class SocialAccountDeletion {

    private final JdbcClient db;
    private final ApplicationEventPublisher events;
    private final DeletionAcknowledgements acknowledgements;

    SocialAccountDeletion(JdbcClient db, ApplicationEventPublisher events, DeletionAcknowledgements acknowledgements) {
        this.db = db;
        this.events = events;
        this.acknowledgements = acknowledgements;
    }

    @ApplicationModuleListener
    void on(UserDeletionRequested event) {
        UUID user = event.userId();
        // Replies under the user's comments go with them (ON DELETE CASCADE), so count every affected video.
        List<UUID> videos = db.sql("""
                SELECT DISTINCT video_id FROM social.comments
                WHERE author_id = :u OR parent_id IN (SELECT id FROM social.comments WHERE author_id = :u)
                """).param("u", user).query(UUID.class).list();
        db.sql("DELETE FROM social.comments WHERE author_id = :u").param("u", user).update();
        db.sql("DELETE FROM social.follows WHERE follower_id = :u OR followee_id = :u").param("u", user).update();
        db.sql("DELETE FROM social.blocks WHERE blocker_id = :u OR blocked_id = :u").param("u", user).update();
        videos.forEach(video -> events.publishEvent(new CommentCountChanged(video)));
        acknowledgements.acknowledge(event.requestId(), "social");
    }
}
