package com.protik.video.captions;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for captions, their cues and transcript search (ADR 0010, ADR 0023). Callers own the transaction. */
@Repository
class CaptionRepository {

    record Listed(UUID id, String language, String source, String status) {
    }

    record Hit(UUID videoId, String language, String source, int startMs, String text, double rank) {
    }

    private final JdbcClient db;

    CaptionRepository(JdbcClient db) {
        this.db = db;
    }

    boolean languageExists(String language) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM video.languages WHERE code = :c)").param("c", language)
                .query(Boolean.class).single();
    }

    /** Replaces the caption of this video, language and source; the old row's file is tombstoned by a trigger. */
    void replace(UUID captionId, UUID videoId, String language, String source, String model, String modelVersion,
            Double confidence, String vttKey, List<Cue> cues) {
        db.sql("DELETE FROM video.captions WHERE video_id = :v AND language_code = :l AND source = :s")
                .param("v", videoId).param("l", language).param("s", source).update();
        db.sql("""
                INSERT INTO video.captions (id, video_id, language_code, source, status, model, model_version, avg_confidence,
                                            vtt_storage_key)
                VALUES (:id, :v, :l, :s, 'READY', :model, :version, :conf, :key)
                """)
                .param("id", captionId).param("v", videoId).param("l", language).param("s", source).param("model", model)
                .param("version", modelVersion).param("conf", confidence).param("key", vttKey).update();
        int[] starts = new int[cues.size()];
        int[] ends = new int[cues.size()];
        String[] texts = new String[cues.size()];
        for (int i = 0; i < cues.size(); i++) {
            starts[i] = cues.get(i).startMs();
            ends[i] = cues.get(i).endMs();
            texts[i] = cues.get(i).text();
        }
        db.sql("""
                INSERT INTO video.caption_segments (caption_id, seq, start_ms, end_ms, text)
                SELECT :id, ord - 1, s, e, t
                FROM unnest(CAST(:starts AS int[]), CAST(:ends AS int[]), CAST(:texts AS text[])) WITH ORDINALITY AS u(s, e, t, ord)
                """)
                .param("id", captionId).param("starts", starts).param("ends", ends).param("texts", texts).update();
    }

    List<Listed> list(UUID videoId) {
        return db.sql("SELECT id, language_code, source, status FROM video.captions WHERE video_id = :v "
                        + "ORDER BY language_code, source")
                .param("v", videoId)
                .query((rs, n) -> new Listed(rs.getObject("id", UUID.class), rs.getString("language_code"),
                        rs.getString("source"), rs.getString("status")))
                .list();
    }

    /** The caption to show: the creator's own if there is one, otherwise the machine's. */
    Optional<String> vttKey(UUID videoId, String language) {
        return db.sql("""
                SELECT vtt_storage_key FROM video.captions
                WHERE video_id = :v AND language_code = :l AND status = 'READY'
                ORDER BY (source = 'CREATOR') DESC LIMIT 1
                """)
                .param("v", videoId).param("l", language).query(String.class).optional();
    }

    /**
     * Accent-insensitive search of published videos' captions (ADR 0023 decision 4). The query goes through the same
     * unaccent and 'simple' text-search configuration as the stored text, so "omo" finds "ọmọ".
     */
    List<Hit> search(String query, String language, int limit) {
        var sql = new StringBuilder("""
                SELECT c.video_id, c.language_code, c.source, s.start_ms, s.text,
                       ts_rank(s.text_tsv, q.query) AS rank
                FROM video.caption_segments s
                JOIN video.captions c ON c.id = s.caption_id AND c.status = 'READY'
                JOIN video.videos v ON v.id = c.video_id AND v.status = 'PUBLISHED'
                CROSS JOIN (SELECT plainto_tsquery('simple', shared.immutable_unaccent(:q)) AS query) q
                WHERE s.text_tsv @@ q.query
                """);
        if (language != null) {
            sql.append(" AND c.language_code = :language");
        }
        sql.append(" ORDER BY rank DESC, c.video_id, s.start_ms LIMIT :limit");
        var statement = db.sql(sql.toString()).param("q", query).param("limit", limit);
        if (language != null) {
            statement = statement.param("language", language);
        }
        return statement.query((rs, n) -> new Hit(rs.getObject("video_id", UUID.class), rs.getString("language_code"),
                rs.getString("source"), rs.getInt("start_ms"), rs.getString("text"), rs.getDouble("rank"))).list();
    }
}
