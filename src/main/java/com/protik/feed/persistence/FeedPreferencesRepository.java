package com.protik.feed.persistence;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL for ranking modes, components and the per-user choices (ADR 0010, ADR 0014). */
@Repository
public class FeedPreferencesRepository {

    public record Mode(String code, String description, boolean followedOnly) {
    }

    public record Component(String code, String description) {
    }

    private final JdbcClient db;

    FeedPreferencesRepository(JdbcClient db) {
        this.db = db;
    }

    public List<Mode> findModes() {
        return db.sql("SELECT code, description, followed_only FROM feed.ranking_modes ORDER BY code")
                .query((rs, n) -> new Mode(rs.getString("code"), rs.getString("description"),
                        rs.getBoolean("followed_only")))
                .list();
    }

    public Optional<Mode> findMode(String code) {
        return db.sql("SELECT code, description, followed_only FROM feed.ranking_modes WHERE code = :code")
                .param("code", code)
                .query((rs, n) -> new Mode(rs.getString("code"), rs.getString("description"),
                        rs.getBoolean("followed_only")))
                .optional();
    }

    /** In a stable order (by code) so responses and explanations do not shuffle. */
    public List<Component> findComponents() {
        return db.sql("SELECT code, description FROM feed.ranking_components ORDER BY code")
                .query((rs, n) -> new Component(rs.getString("code"), rs.getString("description")))
                .list();
    }

    /** mode code -> (component code -> weight) */
    public Map<String, Map<String, BigDecimal>> findModeWeights() {
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        db.sql("SELECT mode_code, component_code, weight FROM feed.ranking_mode_weights ORDER BY mode_code, component_code")
                .query((rs, n) -> {
                    result.computeIfAbsent(rs.getString("mode_code"), k -> new LinkedHashMap<>())
                            .put(rs.getString("component_code"), rs.getBigDecimal("weight"));
                    return null;
                }).list();
        return result;
    }

    public Optional<String> findChosenMode(UUID userId) {
        return db.sql("SELECT ranking_mode FROM feed.user_feed_settings WHERE user_id = :u")
                .param("u", userId).query(String.class).optional();
    }

    public Map<String, BigDecimal> findOverrides(UUID userId) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        db.sql("SELECT component_code, weight FROM feed.user_ranking_weights WHERE user_id = :u ORDER BY component_code")
                .param("u", userId)
                .query((rs, n) -> {
                    result.put(rs.getString("component_code"), rs.getBigDecimal("weight"));
                    return null;
                }).list();
        return result;
    }

    /**
     * Sets the user's mode, creating their settings row on first use. The row lock this takes lasts
     * until the transaction ends, so two simultaneous saves by one user run one after the other.
     * Only the mode column is written: other settings (data saver, caps) belong to other tickets.
     */
    public void upsertMode(UUID userId, String mode) {
        db.sql("""
                INSERT INTO feed.user_feed_settings (user_id, ranking_mode) VALUES (:u, :m)
                ON CONFLICT (user_id) DO UPDATE SET ranking_mode = EXCLUDED.ranking_mode
                """)
                .param("u", userId).param("m", mode).update();
    }

    public void deleteOverrides(UUID userId) {
        db.sql("DELETE FROM feed.user_ranking_weights WHERE user_id = :u").param("u", userId).update();
    }

    public void insertOverride(UUID userId, String component, BigDecimal weight) {
        db.sql("INSERT INTO feed.user_ranking_weights (user_id, component_code, weight) VALUES (:u, :c, :w)")
                .param("u", userId).param("c", component).param("w", weight).update();
    }
}
