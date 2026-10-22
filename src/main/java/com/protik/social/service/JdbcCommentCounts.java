package com.protik.social.service;

import com.protik.social.CommentCounts;
import com.protik.social.persistence.CommentRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcCommentCounts implements CommentCounts {

    private final CommentRepository comments;

    JdbcCommentCounts(CommentRepository comments) {
        this.comments = comments;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> countVisible(Collection<UUID> videoIds) {
        Map<UUID, Long> counts = new HashMap<>(comments.countVisible(videoIds));
        videoIds.forEach(id -> counts.putIfAbsent(id, 0L));
        return counts;
    }
}
