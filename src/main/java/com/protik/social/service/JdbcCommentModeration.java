package com.protik.social.service;

import com.protik.social.CommentCountChanged;
import com.protik.social.CommentModeration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcCommentModeration implements CommentModeration {

    private final JdbcClient db;
    private final ApplicationEventPublisher events;

    JdbcCommentModeration(JdbcClient db, ApplicationEventPublisher events) {
        this.db = db;
        this.events = events;
    }

    @Override
    public boolean exists(UUID commentId) {
        return authorOf(commentId).isPresent();
    }

    @Override
    public Optional<UUID> authorOf(UUID commentId) {
        return db.sql("SELECT author_id FROM social.comments WHERE id = :id").param("id", commentId)
                .query(UUID.class).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hide(UUID commentId) {
        // A hidden top-level comment takes its replies with it, as a deleted one does.
        var videoId = db.sql("""
                UPDATE social.comments SET status = 'HIDDEN'
                WHERE (id = :id OR parent_id = :id) AND status = 'VISIBLE'
                RETURNING video_id
                """).param("id", commentId).query(UUID.class).list();
        if (videoId.isEmpty()) {
            return false;
        }
        events.publishEvent(new CommentCountChanged(videoId.getFirst()));
        return true;
    }
}
