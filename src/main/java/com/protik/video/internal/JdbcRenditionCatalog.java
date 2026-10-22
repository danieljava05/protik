package com.protik.video.internal;

import com.protik.video.Rendition;
import com.protik.video.RenditionCatalog;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcRenditionCatalog implements RenditionCatalog {

    private final JdbcClient db;

    JdbcRenditionCatalog(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<Rendition> all() {
        return db.sql("SELECT code, height_px, max_bitrate_kbps FROM video.renditions ORDER BY max_bitrate_kbps, code")
                .query((rs, n) -> new Rendition(rs.getString("code"), (Integer) rs.getObject("height_px"),
                        rs.getInt("max_bitrate_kbps")))
                .list();
    }
}
