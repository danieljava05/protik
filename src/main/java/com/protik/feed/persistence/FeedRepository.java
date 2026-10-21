package com.protik.feed.persistence;

import com.protik.feed.Candidate;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes the feed's own read models (ADR 0005 decision 4, ADR 0010). */
@Repository
public class FeedRepository {

    /** A candidate plus the display facts the feed returns but the ranker does not use. */
    public record PoolEntry(Candidate candidate, int durationMs, String language) {
    }

    private final JdbcClient db;

    FeedRepository(JdbcClient db) {
        this.db = db;
    }

    // ---- reading for a feed request ----

    /**
     * The newest candidates published at or before {@code asOf}, within {@code window}, optionally only
     * from creators the viewer follows. Ranking happens in memory over this bounded pool.
     */
    public List<PoolEntry> pool(UUID userId, Instant asOf, Duration window, int size, boolean followedOnly) {
        var sql = new StringBuilder("""
                SELECT c.video_id, c.creator_id, c.language, c.published_at, c.duration_ms,
                       COALESCE(s.likes, 0) AS likes, COALESCE(s.comments, 0) AS comments, COALESCE(s.views, 0) AS views
                FROM feed.candidates c
                LEFT JOIN feed.candidate_stats s ON s.video_id = c.video_id
                WHERE c.published_at <= :asOf AND c.published_at > :since
                """);
        if (followedOnly) {
            sql.append(" AND c.creator_id IN (SELECT f.creator_id FROM feed.following f WHERE f.user_id = :user)");
        }
        sql.append(" ORDER BY c.published_at DESC, c.video_id DESC LIMIT :size");

        var statement = db.sql(sql.toString())
                .param("asOf", utc(asOf)).param("since", utc(asOf.minus(window))).param("size", size);
        if (followedOnly) {
            statement = statement.param("user", userId);
        }
        List<Object[]> rows = statement.query((rs, n) -> new Object[] {
                rs.getObject("video_id", UUID.class), rs.getObject("creator_id", UUID.class), rs.getString("language"),
                rs.getObject("published_at", OffsetDateTime.class).toInstant(), rs.getInt("duration_ms"),
                rs.getLong("likes"), rs.getLong("comments"), rs.getLong("views")}).list();
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, Set<String>> tags = tagsOf(rows.stream().map(r -> (UUID) r[0]).toList());
        return rows.stream().map(r -> new PoolEntry(
                new Candidate((UUID) r[0], (UUID) r[1], (Instant) r[3], (Long) r[5], (Long) r[6], (Long) r[7],
                        tags.getOrDefault((UUID) r[0], Set.of())),
                (Integer) r[4], (String) r[2])).toList();
    }

    private Map<UUID, Set<String>> tagsOf(Collection<UUID> videoIds) {
        Map<UUID, Set<String>> result = new HashMap<>();
        db.sql("SELECT video_id, tag FROM feed.candidate_tags WHERE video_id IN (:ids)").param("ids", videoIds)
                .query((rs, n) -> {
                    result.computeIfAbsent(rs.getObject("video_id", UUID.class), k -> new HashSet<>())
                            .add(rs.getString("tag"));
                    return null;
                }).list();
        return result;
    }

    public Set<UUID> followedCreators(UUID userId) {
        return db.sql("SELECT creator_id FROM feed.following WHERE user_id = :u").param("u", userId)
                .query(UUID.class).set();
    }

    public Set<String> interests(UUID userId) {
        return db.sql("SELECT tag FROM feed.user_interests WHERE user_id = :u").param("u", userId)
                .query(String.class).set();
    }

    // ---- writing the read models (idempotent: events are delivered at least once) ----

    public void upsertCandidate(UUID videoId, UUID creatorId, String language, Instant publishedAt, int durationMs) {
        db.sql("""
                INSERT INTO feed.candidates (video_id, creator_id, language, published_at, duration_ms)
                VALUES (:id, :creator, :language, :published, :duration)
                ON CONFLICT (video_id) DO UPDATE SET creator_id = EXCLUDED.creator_id, language = EXCLUDED.language,
                    published_at = EXCLUDED.published_at, duration_ms = EXCLUDED.duration_ms
                """)
                .param("id", videoId).param("creator", creatorId).param("language", language)
                .param("published", utc(publishedAt)).param("duration", durationMs).update();
        db.sql("INSERT INTO feed.candidate_stats (video_id) VALUES (:id) ON CONFLICT (video_id) DO NOTHING")
                .param("id", videoId).update();
    }

    public void replaceTags(UUID videoId, Collection<String> tags) {
        db.sql("DELETE FROM feed.candidate_tags WHERE video_id = :id").param("id", videoId).update();
        for (String tag : tags) {
            db.sql("INSERT INTO feed.candidate_tags (video_id, tag) VALUES (:id, :tag)")
                    .param("id", videoId).param("tag", tag).update();
        }
    }

    public int deleteCandidate(UUID videoId) {
        return db.sql("DELETE FROM feed.candidates WHERE video_id = :id").param("id", videoId).update();
    }

    public void setStats(UUID videoId, long likes, long comments, long views) {
        db.sql("""
                UPDATE feed.candidate_stats SET likes = :likes, comments = :comments, views = :views, as_of = now()
                WHERE video_id = :id
                """)
                .param("id", videoId).param("likes", likes).param("comments", comments).param("views", views).update();
    }

    public void addFollow(UUID userId, UUID creatorId) {
        db.sql("INSERT INTO feed.following (user_id, creator_id) VALUES (:u, :c) ON CONFLICT DO NOTHING")
                .param("u", userId).param("c", creatorId).update();
    }

    public void removeFollow(UUID userId, UUID creatorId) {
        db.sql("DELETE FROM feed.following WHERE user_id = :u AND creator_id = :c")
                .param("u", userId).param("c", creatorId).update();
    }

    public void replaceInterests(UUID userId, Collection<String> tags) {
        db.sql("DELETE FROM feed.user_interests WHERE user_id = :u").param("u", userId).update();
        for (String tag : tags) {
            db.sql("INSERT INTO feed.user_interests (user_id, tag) VALUES (:u, :tag)").param("u", userId)
                    .param("tag", tag).update();
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
