package com.protik.feed.service;

import com.protik.identity.DeletionAcknowledgements;
import com.protik.identity.UserDeletionRequested;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Deletes a deleted user's settings, usage reports, seen list, interests, likes and follow-graph copies (ADR 0020). */
@Component
class FeedAccountDeletion {

    private final JdbcClient db;
    private final DeletionAcknowledgements acknowledgements;

    FeedAccountDeletion(JdbcClient db, DeletionAcknowledgements acknowledgements) {
        this.db = db;
        this.acknowledgements = acknowledgements;
    }

    @ApplicationModuleListener
    void on(UserDeletionRequested event) {
        UUID user = event.userId();
        // Ranking weights go with the settings row (ON DELETE CASCADE).
        db.sql("DELETE FROM feed.user_feed_settings WHERE user_id = :u").param("u", user).update();
        db.sql("DELETE FROM feed.usage_events WHERE user_id = :u").param("u", user).update();
        db.sql("DELETE FROM feed.seen_videos WHERE user_id = :u").param("u", user).update();
        db.sql("DELETE FROM feed.following WHERE user_id = :u OR creator_id = :u").param("u", user).update();
        db.sql("DELETE FROM feed.user_interests WHERE user_id = :u").param("u", user).update();
        db.sql("DELETE FROM feed.user_likes WHERE user_id = :u").param("u", user).update();
        acknowledgements.acknowledge(event.requestId(), "feed");
    }
}
