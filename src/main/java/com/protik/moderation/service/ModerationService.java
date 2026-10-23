package com.protik.moderation.service;

import com.protik.identity.AccountAdministration;
import com.protik.moderation.persistence.ModerationRepository;
import com.protik.moderation.persistence.ModerationRepository.Action;
import com.protik.moderation.persistence.ModerationRepository.Report;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.error.RateLimitedException;
import com.protik.shared.id.Ids;
import com.protik.shared.ratelimit.RateLimitKeys;
import com.protik.shared.ratelimit.RateLimiter;
import com.protik.social.CommentModeration;
import com.protik.video.VideoCatalog;
import com.protik.video.VideoInfo;
import com.protik.video.VideoModeration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reports, the moderator queue and moderator actions with their audit trail (ADR 0019). */
@Service
public class ModerationService {

    public static final Set<String> TARGETS = Set.of("VIDEO", "COMMENT", "ACCOUNT");
    public static final Set<String> REASONS = Set.of("CHILD_SAFETY", "COPYRIGHT", "HARASSMENT", "VIOLENCE", "SPAM", "OTHER");
    public static final Set<String> ACTIONS = Set.of("TAKEDOWN", "HIDE_COMMENT", "SUSPEND_ACCOUNT", "DISMISS");
    static final int REPORTS_PER_HOUR = 10;
    static final int MAX_REASON = 1000;
    static final int MAX_PAGE = 100;

    public record Page(List<Report> items, String nextCursor) {
    }

    private final ModerationRepository repository;
    private final VideoCatalog videos;
    private final VideoModeration videoModeration;
    private final CommentModeration comments;
    private final AccountAdministration accounts;
    private final RateLimiter limiter;

    ModerationService(ModerationRepository repository, VideoCatalog videos, VideoModeration videoModeration,
            CommentModeration comments, AccountAdministration accounts, RateLimiter limiter) {
        this.repository = repository;
        this.videos = videos;
        this.videoModeration = videoModeration;
        this.comments = comments;
        this.accounts = accounts;
        this.limiter = limiter;
    }

    /** Counts a report against the user's limit. Call before opening the transaction (the limiter commits itself). */
    public void beginReport(UUID reporterId) {
        limiter.acquire(List.of(new RateLimiter.Limit(RateLimitKeys.of("report-user", reporterId.toString()),
                        REPORTS_PER_HOUR, Duration.ofHours(1))))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
    }

    @Transactional
    public UUID report(UUID reporterId, String targetType, UUID targetId, String reason, String details) {
        if (targetType == null || !TARGETS.contains(targetType) || targetId == null) {
            throw invalid("targetType must be one of " + TARGETS + " and targetId is required.");
        }
        if (reason == null || !REASONS.contains(reason)) {
            throw invalid("reason must be one of " + REASONS + ".");
        }
        String text = details == null ? "" : details.strip();
        if (text.length() > 2000) {
            throw invalid("details must be at most 2000 characters.");
        }
        UUID owner = switch (targetType) {
            case "VIDEO" -> videos.find(targetId).map(VideoInfo::creatorId).orElseThrow(ModerationService::missing);
            case "COMMENT" -> comments.authorOf(targetId).orElseThrow(ModerationService::missing);
            default -> {
                if (!accounts.exists(targetId)) {
                    throw missing();
                }
                yield targetId;
            }
        };
        if (owner.equals(reporterId)) {
            throw invalid("You cannot report your own content or account.");
        }
        UUID id = Ids.newId();
        if (!repository.insertReport(id, reporterId, targetType, targetId, reason, text)) {
            throw new ApiException(ProblemType.CONFLICT, "You already have an open report about this.");
        }
        return id;
    }

    @Transactional(readOnly = true)
    public Page queue(Integer limit, String cursor) {
        int size = limit == null ? 25 : limit;
        if (size < 1 || size > MAX_PAGE) {
            throw invalid("limit must be between 1 and " + MAX_PAGE + ".");
        }
        Integer rank = null;
        Instant at = null;
        UUID id = null;
        if (cursor != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                rank = Integer.parseInt(parts[0]);
                at = Instant.parse(parts[1]);
                id = UUID.fromString(parts[2]);
            } catch (RuntimeException e) {
                throw invalid("The cursor is not valid.");
            }
        }
        List<Report> rows = repository.queue(size + 1, rank, at, id);
        if (rows.size() <= size) {
            return new Page(rows, null);
        }
        List<Report> items = List.copyOf(rows.subList(0, size));
        Report last = items.getLast();
        String raw = (last.reason().equals("CHILD_SAFETY") ? 0 : 1) + "|" + last.createdAt() + "|" + last.id();
        return new Page(items, Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Resolves a report. The effect on the target, the report's new status and the audit row commit together: the
     * log cannot miss an action that happened nor record one that did not.
     */
    @Transactional
    public Action act(UUID moderatorId, UUID reportId, String action, String reason) {
        if (action == null || !ACTIONS.contains(action)) {
            throw invalid("action must be one of " + ACTIONS + ".");
        }
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > MAX_REASON) {
            throw invalid("A reason of 1 to " + MAX_REASON + " characters is required.");
        }
        Report report = repository.findReportForUpdate(reportId).orElseThrow(ModerationService::missing);
        if (!"OPEN".equals(report.status())) {
            throw new ApiException(ProblemType.CONFLICT, "This report has already been resolved.");
        }
        String expectedTarget = switch (action) {
            case "TAKEDOWN" -> "VIDEO";
            case "HIDE_COMMENT" -> "COMMENT";
            case "SUSPEND_ACCOUNT" -> "ACCOUNT";
            default -> report.targetType();
        };
        if (!expectedTarget.equals(report.targetType())) {
            throw invalid(action + " applies to a " + expectedTarget + " report, not a " + report.targetType() + " report.");
        }
        switch (action) {
            case "TAKEDOWN" -> videoModeration.takeDown(report.targetId());
            case "HIDE_COMMENT" -> comments.hide(report.targetId());
            case "SUSPEND_ACCOUNT" -> accounts.suspend(report.targetId());
            default -> {
            }
        }
        String status = "DISMISS".equals(action) ? "DISMISSED" : "ACTIONED";
        repository.setStatus(reportId, status);
        repository.settleOthers(report.targetType(), report.targetId(), status);
        UUID actionId = Ids.newId();
        String recorded = "DISMISS".equals(action) ? "DISMISS_REPORT" : action;
        repository.insertAction(actionId, reportId, moderatorId, recorded, report.targetType(), report.targetId(), why);
        return repository.history(report.targetType(), report.targetId()).stream()
                .filter(a -> a.id().equals(actionId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Action> history(String targetType, UUID targetId) {
        if (targetType == null || !TARGETS.contains(targetType) || targetId == null) {
            throw invalid("targetType must be one of " + TARGETS + " and targetId is required.");
        }
        return repository.history(targetType, targetId);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ProblemType.VALIDATION_FAILED, detail);
    }

    private static ApiException missing() {
        return new ApiException(ProblemType.NOT_FOUND, "Not found.");
    }
}
