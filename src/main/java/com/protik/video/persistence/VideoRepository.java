package com.protik.video.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for uploads, processing and serving (ADR 0010, ADR 0022). Callers own the transaction. */
@Repository
public class VideoRepository {

    public record Row(UUID id, UUID creatorId, String status, String language, String scanStatus) {
    }

    public record Asset(String storageKey, long sizeBytes) {
    }

    private final JdbcClient db;

    VideoRepository(JdbcClient db) {
        this.db = db;
    }

    public boolean languageExists(String code) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM video.languages WHERE code = :c)").param("c", code)
                .query(Boolean.class).single();
    }

    public void insertUploading(UUID id, UUID creatorId, String description, String language) {
        db.sql("INSERT INTO video.videos (id, creator_id, description, spoken_language, status) "
                        + "VALUES (:id, :creator, :description, :language, 'UPLOADING')")
                .param("id", id).param("creator", creatorId).param("description", description)
                .param("language", language).update();
    }

    public Optional<Row> find(UUID id) {
        return db.sql("SELECT id, creator_id, status, spoken_language, scan_status FROM video.videos WHERE id = :id")
                .param("id", id).query((rs, n) -> row(rs)).optional();
    }

    public Optional<Row> lock(UUID id) {
        return db.sql("SELECT id, creator_id, status, spoken_language, scan_status FROM video.videos WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, n) -> row(rs)).optional();
    }

    public void markProcessing(UUID id) {
        db.sql("UPDATE video.videos SET status = 'PROCESSING' WHERE id = :id").param("id", id).update();
    }

    public void setScan(UUID id, String scanStatus) {
        db.sql("UPDATE video.videos SET scan_status = :s WHERE id = :id").param("s", scanStatus).param("id", id).update();
    }

    public void markFailed(UUID id, String reason) {
        db.sql("UPDATE video.videos SET status = 'FAILED', failure_reason = :r WHERE id = :id")
                .param("r", reason.length() > 200 ? reason.substring(0, 200) : reason).param("id", id).update();
    }

    /** Records (or re-records, so a re-run changes nothing) one stored file of a video. */
    public void upsertAsset(UUID id, UUID videoId, String kind, String rendition, String key, long size, Integer avgKbps) {
        db.sql("""
                INSERT INTO video.video_assets (id, video_id, kind, rendition_code, storage_key, size_bytes, avg_bitrate_kbps)
                VALUES (:id, :video, :kind, :rendition, :key, :size, :kbps)
                ON CONFLICT (video_id, kind, rendition_code) DO UPDATE
                    SET storage_key = EXCLUDED.storage_key, size_bytes = EXCLUDED.size_bytes,
                        avg_bitrate_kbps = EXCLUDED.avg_bitrate_kbps
                """)
                .param("id", id).param("video", videoId).param("kind", kind).param("rendition", rendition)
                .param("key", key).param("size", size).param("kbps", avgKbps).update();
    }

    public Optional<Asset> findAsset(UUID videoId, String kind, String rendition) {
        return db.sql("SELECT storage_key, size_bytes FROM video.video_assets WHERE video_id = :v AND kind = :k "
                        + "AND rendition_code IS NOT DISTINCT FROM :r")
                .param("v", videoId).param("k", kind).param("r", rendition)
                .query((rs, n) -> new Asset(rs.getString("storage_key"), rs.getLong("size_bytes"))).optional();
    }

    public record Variant(String code, long sizeBytes, Integer avgKbps) {
    }

    /** The playable renditions of a video, smallest first. */
    public List<Variant> variants(UUID videoId) {
        return db.sql("SELECT rendition_code, size_bytes, avg_bitrate_kbps FROM video.video_assets "
                        + "WHERE video_id = :v AND kind = 'MP4_VARIANT' ORDER BY size_bytes, rendition_code")
                .param("v", videoId)
                .query((rs, n) -> new Variant(rs.getString("rendition_code"), rs.getLong("size_bytes"),
                        (Integer) rs.getObject("avg_bitrate_kbps")))
                .list();
    }

    public record Details(Row row, String description, String failureReason, Integer durationMs) {
    }

    public Optional<Details> details(UUID id) {
        return db.sql("SELECT id, creator_id, status, spoken_language, scan_status, description, failure_reason, duration_ms "
                        + "FROM video.videos WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Details(row(rs), rs.getString("description"), rs.getString("failure_reason"),
                        (Integer) rs.getObject("duration_ms")))
                .optional();
    }

    /** Removes the asset row (its key is recorded as a tombstone by the trigger). */
    public void deleteAsset(UUID videoId, String kind) {
        db.sql("DELETE FROM video.video_assets WHERE video_id = :v AND kind = :k").param("v", videoId).param("k", kind).update();
    }

    public Set<String> tags(UUID videoId) {
        return Set.copyOf(db.sql("SELECT h.tag::text FROM video.video_hashtags vh JOIN video.hashtags h ON h.id = vh.hashtag_id "
                + "WHERE vh.video_id = :v").param("v", videoId).query(String.class).list());
    }

    /** @return false if the video was not in PROCESSING (already published, failed or taken down) */
    public boolean publish(UUID id, int durationMs, Instant publishedAt) {
        return db.sql("UPDATE video.videos SET status = 'PUBLISHED', duration_ms = :d, published_at = :at "
                        + "WHERE id = :id AND status = 'PROCESSING'")
                .param("d", durationMs).param("at", publishedAt.atOffset(ZoneOffset.UTC)).param("id", id)
                .update() == 1;
    }

    public List<String> tombstones(int limit) {
        return db.sql("SELECT storage_key FROM video.storage_tombstones ORDER BY created_at LIMIT :n").param("n", limit)
                .query(String.class).list();
    }

    public void deleteTombstone(String key) {
        db.sql("DELETE FROM video.storage_tombstones WHERE storage_key = :k").param("k", key).update();
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("creator_id", UUID.class), rs.getString("status"),
                rs.getString("spoken_language"), rs.getString("scan_status"));
    }
}
