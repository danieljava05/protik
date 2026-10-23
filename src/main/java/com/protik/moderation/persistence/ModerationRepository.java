package com.protik.moderation.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the moderation schema (ADR 0010, ADR 0019). Callers own the transaction. */
@Repository
public class ModerationRepository {

    public record Report(UUID id, UUID reporterId, String targetType, UUID targetId, String reason, String details,
            String status, Instant createdAt) {
    }

    public record Action(UUID id, UUID reportId, UUID actorId, String actionType, String targetType, UUID targetId,
            String reason, Instant occurredAt) {
    }

    private static final String RANK = "(CASE WHEN reason_code = 'CHILD_SAFETY' THEN 0 ELSE 1 END)";
    private static final String REPORT_COLUMNS =
            "id, reporter_id, target_type, target_id, reason_code, details, status, created_at";

    private final JdbcClient db;

    ModerationRepository(JdbcClient db) {
        this.db = db;
    }

    /** @return false if this reporter already has an open report about the target */
    public boolean insertReport(UUID id, UUID reporterId, String targetType, UUID targetId, String reason, String details) {
        return db.sql("""
                INSERT INTO moderation.reports (id, reporter_id, target_type, target_id, reason_code, details)
                VALUES (:id, :reporter, :type, :target, :reason, :details)
                ON CONFLICT DO NOTHING
                """)
                .param("id", id).param("reporter", reporterId).param("type", targetType).param("target", targetId)
                .param("reason", reason).param("details", details).update() == 1;
    }

    /** Locks the report row so two moderators cannot resolve it twice. */
    public Optional<Report> findReportForUpdate(UUID id) {
        return db.sql("SELECT " + REPORT_COLUMNS + " FROM moderation.reports WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, n) -> report(rs)).optional();
    }

    /** Open reports, child safety first, then oldest first, strictly after the cursor position. */
    public List<Report> queue(int limit, Integer afterRank, Instant afterAt, UUID afterId) {
        var sql = new StringBuilder("SELECT " + REPORT_COLUMNS + " FROM moderation.reports WHERE status = 'OPEN'");
        if (afterRank != null) {
            sql.append(" AND (").append(RANK).append(", created_at, id) > (:rank, :at, :cursorId)");
        }
        sql.append(" ORDER BY ").append(RANK).append(", created_at, id LIMIT :limit");
        var statement = db.sql(sql.toString()).param("limit", limit);
        if (afterRank != null) {
            statement = statement.param("rank", afterRank).param("at", afterAt.atOffset(ZoneOffset.UTC))
                    .param("cursorId", afterId);
        }
        return statement.query((rs, n) -> report(rs)).list();
    }

    public void setStatus(UUID reportId, String status) {
        db.sql("UPDATE moderation.reports SET status = :s WHERE id = :id").param("s", status).param("id", reportId).update();
    }

    /** Other open reports about the same target are settled by the same decision. */
    public int settleOthers(String targetType, UUID targetId, String status) {
        return db.sql("UPDATE moderation.reports SET status = :s WHERE target_type = :t AND target_id = :id AND status = 'OPEN'")
                .param("s", status).param("t", targetType).param("id", targetId).update();
    }

    public void insertAction(UUID id, UUID reportId, UUID actorId, String actionType, String targetType, UUID targetId,
            String reason) {
        db.sql("""
                INSERT INTO moderation.actions (id, report_id, actor_id, action_type, target_type, target_id, reason)
                VALUES (:id, :report, :actor, :type, :targetType, :target, :reason)
                """)
                .param("id", id).param("report", reportId).param("actor", actorId).param("type", actionType)
                .param("targetType", targetType).param("target", targetId).param("reason", reason).update();
    }

    public List<Action> history(String targetType, UUID targetId) {
        return db.sql("""
                SELECT id, report_id, actor_id, action_type, target_type, target_id, reason, occurred_at
                FROM moderation.actions WHERE target_type = :t AND target_id = :id ORDER BY occurred_at, id
                """)
                .param("t", targetType).param("id", targetId)
                .query((rs, n) -> new Action(rs.getObject("id", UUID.class), rs.getObject("report_id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getString("action_type"), rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class), rs.getString("reason"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static Report report(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Report(rs.getObject("id", UUID.class), rs.getObject("reporter_id", UUID.class),
                rs.getString("target_type"), rs.getObject("target_id", UUID.class), rs.getString("reason_code"),
                rs.getString("details"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
