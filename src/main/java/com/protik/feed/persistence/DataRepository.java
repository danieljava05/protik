package com.protik.feed.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for a viewer's data settings and reported usage (ADR 0010, ADR 0018). Callers own the transaction. */
@Repository
public class DataRepository {

    /** The data-related columns of {@code feed.user_feed_settings}. */
    public record Settings(boolean dataSaver, Integer monthlyCapMb, int prefetchCapMb, int billingDay) {
    }

    private final JdbcClient db;

    DataRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<Settings> findSettings(UUID userId) {
        return db.sql("""
                SELECT data_saver, monthly_data_cap_mb, prefetch_cap_mb, billing_day
                FROM feed.user_feed_settings WHERE user_id = :u
                """)
                .param("u", userId)
                .query((rs, n) -> new Settings(rs.getBoolean("data_saver"), (Integer) rs.getObject("monthly_data_cap_mb"),
                        rs.getInt("prefetch_cap_mb"), rs.getInt("billing_day")))
                .optional();
    }

    /** Writes only the data columns, so the viewer's ranking mode is untouched (and the other way round). */
    public void saveSettings(UUID userId, Settings settings) {
        db.sql("""
                INSERT INTO feed.user_feed_settings (user_id, data_saver, monthly_data_cap_mb, prefetch_cap_mb, billing_day)
                VALUES (:u, :saver, :cap, :prefetch, :day)
                ON CONFLICT (user_id) DO UPDATE SET data_saver = EXCLUDED.data_saver,
                    monthly_data_cap_mb = EXCLUDED.monthly_data_cap_mb, prefetch_cap_mb = EXCLUDED.prefetch_cap_mb,
                    billing_day = EXCLUDED.billing_day
                """)
                .param("u", userId).param("saver", settings.dataSaver()).param("cap", settings.monthlyCapMb())
                .param("prefetch", settings.prefetchCapMb()).param("day", settings.billingDay()).update();
    }

    /** @return true if the event was new (a repeated ID with the same time is ignored) */
    public boolean insertUsage(UUID eventId, UUID userId, UUID videoId, String rendition, long bytes, Instant occurredAt) {
        return db.sql("""
                INSERT INTO feed.usage_events (id, user_id, video_id, rendition_code, bytes, occurred_at)
                VALUES (:id, :u, :v, :r, :bytes, :at)
                ON CONFLICT DO NOTHING
                """)
                .param("id", eventId).param("u", userId).param("v", videoId).param("r", rendition)
                .param("bytes", bytes).param("at", utc(occurredAt)).update() == 1;
    }

    /** Bytes used per rendition in [from, to). */
    public Map<String, Long> usageByRendition(UUID userId, Instant from, Instant to) {
        Map<String, Long> result = new LinkedHashMap<>();
        db.sql("""
                SELECT rendition_code, sum(bytes) AS total FROM feed.usage_events
                WHERE user_id = :u AND occurred_at >= :from AND occurred_at < :to
                GROUP BY rendition_code ORDER BY rendition_code
                """)
                .param("u", userId).param("from", utc(from)).param("to", utc(to))
                .query((rs, n) -> {
                    result.put(rs.getString("rendition_code"), rs.getLong("total"));
                    return null;
                }).list();
        return result;
    }

    public long usageTotal(UUID userId, Instant from, Instant to) {
        return db.sql("""
                SELECT COALESCE(sum(bytes), 0) FROM feed.usage_events
                WHERE user_id = :u AND occurred_at >= :from AND occurred_at < :to
                """)
                .param("u", userId).param("from", utc(from)).param("to", utc(to)).query(Long.class).single();
    }

    public int purgeUsageOlderThan(Duration age) {
        return db.sql("DELETE FROM feed.usage_events WHERE occurred_at < now() - make_interval(secs => :age)")
                .param("age", age.toMillis() / 1000.0).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
