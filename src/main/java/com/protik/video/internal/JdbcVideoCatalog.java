package com.protik.video.internal;

import com.protik.video.VideoCatalog;
import com.protik.video.VideoInfo;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcVideoCatalog implements VideoCatalog {

    private final JdbcClient db;

    JdbcVideoCatalog(JdbcClient db) {
        this.db = db;
    }

    @Override
    public Optional<VideoInfo> find(UUID videoId) {
        return db.sql("SELECT id, creator_id, status = 'PUBLISHED' AS published FROM video.videos WHERE id = :id")
                .param("id", videoId)
                .query((rs, n) -> new VideoInfo(rs.getObject("id", UUID.class), rs.getObject("creator_id", UUID.class),
                        rs.getBoolean("published")))
                .optional();
    }
}
