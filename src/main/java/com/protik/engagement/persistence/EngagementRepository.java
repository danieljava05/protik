package com.protik.engagement.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for likes and counters (ADR 0010, ADR 0016). Callers own the transaction. */
@Repository
public class EngagementRepository {

    public record Counters(long likes, long comments, long views) {
    }

    private final JdbcClient db;

    EngagementRepository(JdbcClient db) {
        this.db = db;
    }

    /** @return true if the like is new */
    public boolean insertLike(UUID userId, UUID videoId) {
        return db.sql("INSERT INTO engagement.video_likes (user_id, video_id) VALUES (:u, :v) ON CONFLICT DO NOTHING")
                .param("u", userId).param("v", videoId).update() == 1;
    }

    /** @return true if a like was removed */
    public boolean deleteLike(UUID userId, UUID videoId) {
        return db.sql("DELETE FROM engagement.video_likes WHERE user_id = :u AND video_id = :v")
                .param("u", userId).param("v", videoId).update() == 1;
    }

    public boolean isLiked(UUID userId, UUID videoId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM engagement.video_likes WHERE user_id = :u AND video_id = :v)")
                .param("u", userId).param("v", videoId).query(Boolean.class).single();
    }

    public long countLikes(UUID videoId) {
        return db.sql("SELECT count(*) FROM engagement.video_likes WHERE video_id = :v")
                .param("v", videoId).query(Long.class).single();
    }

    public void markDirty(UUID videoId) {
        db.sql("INSERT INTO engagement.dirty_counters (video_id) VALUES (:v) ON CONFLICT DO NOTHING")
                .param("v", videoId).update();
    }

    /**
     * Takes up to {@code limit} dirty videos, oldest first. {@code SKIP LOCKED} lets several instances
     * share the work without waiting for each other. The rows are deleted in the caller's transaction,
     * so if it fails they are dirty again.
     */
    public List<UUID> claimDirty(int limit) {
        return db.sql("""
                DELETE FROM engagement.dirty_counters
                WHERE video_id IN (SELECT video_id FROM engagement.dirty_counters
                                   ORDER BY marked_at FOR UPDATE SKIP LOCKED LIMIT :limit)
                RETURNING video_id
                """)
                .param("limit", limit).query(UUID.class).list();
    }

    public Counters counters(UUID videoId) {
        return db.sql("SELECT likes, comments, views FROM engagement.video_counters WHERE video_id = :v")
                .param("v", videoId)
                .query((rs, n) -> new Counters(rs.getLong("likes"), rs.getLong("comments"), rs.getLong("views")))
                .optional().orElse(new Counters(0, 0, 0));
    }

    public void saveCounts(UUID videoId, long likes, long comments) {
        db.sql("""
                INSERT INTO engagement.video_counters (video_id, likes, comments) VALUES (:v, :likes, :comments)
                ON CONFLICT (video_id) DO UPDATE SET likes = EXCLUDED.likes, comments = EXCLUDED.comments,
                    updated_at = now()
                """)
                .param("v", videoId).param("likes", likes).param("comments", comments).update();
    }

    /** Video IDs that have a counter row, in pages, for the full reconciliation. */
    public List<UUID> counterVideosAfter(UUID after, int limit) {
        return db.sql("SELECT video_id FROM engagement.video_counters WHERE video_id > :after ORDER BY video_id LIMIT :limit")
                .param("after", after).param("limit", limit).query(UUID.class).list();
    }

    public void setComments(UUID videoId, long comments) {
        db.sql("UPDATE engagement.video_counters SET comments = :c, updated_at = now() WHERE video_id = :v")
                .param("v", videoId).param("c", comments).update();
    }

    /**
     * Recomputes every like counter from the truth, repairing any drift, and zeroes counters whose
     * video has no likes left. Returns the videos whose counter changed (so they can be announced).
     */
    public List<UUID> reconcileLikes() {
        List<UUID> changed = new java.util.ArrayList<>(db.sql("""
                UPDATE engagement.video_counters c SET likes = s.n, updated_at = now()
                FROM (SELECT video_id, count(*) AS n FROM engagement.video_likes GROUP BY video_id) s
                WHERE c.video_id = s.video_id AND c.likes <> s.n
                RETURNING c.video_id
                """).query(UUID.class).list());
        changed.addAll(db.sql("""
                UPDATE engagement.video_counters c SET likes = 0, updated_at = now()
                WHERE c.likes <> 0 AND NOT EXISTS (SELECT 1 FROM engagement.video_likes l WHERE l.video_id = c.video_id)
                RETURNING c.video_id
                """).query(UUID.class).list());
        changed.addAll(db.sql("""
                INSERT INTO engagement.video_counters (video_id, likes)
                SELECT video_id, count(*) FROM engagement.video_likes GROUP BY video_id
                ON CONFLICT (video_id) DO NOTHING
                RETURNING video_id
                """).query(UUID.class).list());
        return changed;
    }
}
