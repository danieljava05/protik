package com.protik.social.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code social.comments} (ADR 0010, ADR 0017). Callers own the transaction. */
@Repository
public class CommentRepository {

    /** A stored comment, as the service needs it to make decisions. */
    public record Stored(UUID id, UUID videoId, UUID authorId, UUID parentId, String body, Instant createdAt) {
    }

    /** A comment as listed, with how many visible replies it has (0 for a reply). */
    public record Listed(UUID id, UUID authorId, String body, Instant createdAt, long replyCount) {
    }

    private final JdbcClient db;

    CommentRepository(JdbcClient db) {
        this.db = db;
    }

    public Stored insert(UUID id, UUID videoId, UUID authorId, UUID parentId, String body) {
        return db.sql("""
                INSERT INTO social.comments (id, video_id, author_id, parent_id, body)
                VALUES (:id, :video, :author, :parent, :body)
                RETURNING id, video_id, author_id, parent_id, body, created_at
                """)
                .param("id", id).param("video", videoId).param("author", authorId).param("parent", parentId)
                .param("body", body)
                .query((rs, n) -> stored(rs)).single();
    }

    public Optional<Stored> find(UUID id) {
        return db.sql("""
                SELECT id, video_id, author_id, parent_id, body, created_at
                FROM social.comments WHERE id = :id AND status = 'VISIBLE'
                """)
                .param("id", id).query((rs, n) -> stored(rs)).optional();
    }

    /** @return true if the comment existed (its replies go with it) */
    public boolean delete(UUID id) {
        return db.sql("DELETE FROM social.comments WHERE id = :id").param("id", id).update() == 1;
    }

    /** How many visible comments (replies included) a thread removes when its top-level comment is deleted. */
    public long countThread(UUID topLevelId) {
        return db.sql("SELECT 1 + count(*) FROM social.comments WHERE parent_id = :id AND status = 'VISIBLE'")
                .param("id", topLevelId).query(Long.class).single();
    }

    /** Top-level comments of a video, newest first, strictly after the cursor position. */
    public List<Listed> topLevel(UUID videoId, int limit, Instant afterCreatedAt, UUID afterId) {
        var sql = new StringBuilder("""
                SELECT c.id, c.author_id, c.body, c.created_at,
                       (SELECT count(*) FROM social.comments r WHERE r.parent_id = c.id AND r.status = 'VISIBLE') AS replies
                FROM social.comments c
                WHERE c.video_id = :video AND c.parent_id IS NULL AND c.status = 'VISIBLE'
                """);
        if (afterCreatedAt != null) {
            sql.append(" AND (c.created_at, c.id) < (:at, :cursorId)");
        }
        sql.append(" ORDER BY c.created_at DESC, c.id DESC LIMIT :limit");
        var statement = db.sql(sql.toString()).param("video", videoId).param("limit", limit);
        if (afterCreatedAt != null) {
            statement = statement.param("at", utc(afterCreatedAt)).param("cursorId", afterId);
        }
        return statement.query((rs, n) -> listed(rs)).list();
    }

    /** Replies to a comment, oldest first (a conversation reads forward), strictly after the cursor position. */
    public List<Listed> replies(UUID parentId, int limit, Instant afterCreatedAt, UUID afterId) {
        var sql = new StringBuilder("""
                SELECT c.id, c.author_id, c.body, c.created_at, 0 AS replies
                FROM social.comments c
                WHERE c.parent_id = :parent AND c.status = 'VISIBLE'
                """);
        if (afterCreatedAt != null) {
            sql.append(" AND (c.created_at, c.id) > (:at, :cursorId)");
        }
        sql.append(" ORDER BY c.created_at ASC, c.id ASC LIMIT :limit");
        var statement = db.sql(sql.toString()).param("parent", parentId).param("limit", limit);
        if (afterCreatedAt != null) {
            statement = statement.param("at", utc(afterCreatedAt)).param("cursorId", afterId);
        }
        return statement.query((rs, n) -> listed(rs)).list();
    }

    public Map<UUID, Long> countVisible(Collection<UUID> videoIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (videoIds.isEmpty()) {
            return counts;
        }
        db.sql("SELECT video_id, count(*) AS n FROM social.comments WHERE video_id IN (:ids) AND status = 'VISIBLE' GROUP BY video_id")
                .param("ids", videoIds)
                .query((rs, n) -> {
                    counts.put(rs.getObject("video_id", UUID.class), rs.getLong("n"));
                    return null;
                }).list();
        return counts;
    }

    private static Stored stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stored(rs.getObject("id", UUID.class), rs.getObject("video_id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getObject("parent_id", UUID.class), rs.getString("body"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static Listed listed(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Listed(rs.getObject("id", UUID.class), rs.getObject("author_id", UUID.class), rs.getString("body"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getLong("replies"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
