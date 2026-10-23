package com.protik.video.internal;

import com.protik.video.VideoModeration;
import com.protik.video.VideoRemoved;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcVideoModeration implements VideoModeration {

    private final JdbcClient db;
    private final ApplicationEventPublisher events;

    JdbcVideoModeration(JdbcClient db, ApplicationEventPublisher events) {
        this.db = db;
        this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean takeDown(UUID videoId) {
        int changed = db.sql("UPDATE video.videos SET status = 'TAKEN_DOWN' WHERE id = :id AND status = 'PUBLISHED'")
                .param("id", videoId).update();
        if (changed == 0) {
            return false;
        }
        events.publishEvent(new VideoRemoved(videoId));
        return true;
    }
}
