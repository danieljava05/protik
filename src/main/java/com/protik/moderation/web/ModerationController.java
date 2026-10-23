package com.protik.moderation.web;

import com.protik.moderation.persistence.ModerationRepository.Action;
import com.protik.moderation.persistence.ModerationRepository.Report;
import com.protik.moderation.service.ModerationService;
import com.protik.shared.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reporting (any signed-in user) and the moderator queue (MODERATOR or ADMIN only), ADR 0019. */
@RestController
class ModerationController {

    record ReportRequest(String targetType, UUID targetId, String reason, String details) {
    }

    record Created(UUID id) {
    }

    record ActionRequest(String action, String reason) {
    }

    record ReportItem(UUID id, UUID reporterId, String targetType, UUID targetId, String reason, String details,
            Instant createdAt) {
    }

    record QueuePage(List<ReportItem> items, String nextCursor) {
    }

    record ActionItem(UUID id, UUID reportId, UUID actorId, String action, String targetType, UUID targetId,
            String reason, Instant occurredAt) {
    }

    private final ModerationService moderation;

    ModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @PostMapping("/v1/reports")
    ResponseEntity<Created> report(@AuthenticationPrincipal Jwt token, @RequestBody ReportRequest request) {
        UUID reporter = AuthenticatedUser.id(token);
        moderation.beginReport(reporter);
        UUID id = moderation.report(reporter, request.targetType(), request.targetId(), request.reason(), request.details());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new Created(id));
    }

    @GetMapping("/v1/moderation/reports")
    ResponseEntity<QueuePage> queue(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        var page = moderation.queue(limit, cursor);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new QueuePage(page.items().stream().map(ModerationController::item).toList(), page.nextCursor()));
    }

    @PostMapping("/v1/moderation/reports/{id}/actions")
    ResponseEntity<ActionItem> act(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
            @RequestBody ActionRequest request) {
        Action done = moderation.act(AuthenticatedUser.id(token), id, request.action(), request.reason());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(action(done));
    }

    @GetMapping("/v1/moderation/audit")
    ResponseEntity<List<ActionItem>> audit(@RequestParam String targetType, @RequestParam UUID targetId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(moderation.history(targetType, targetId).stream().map(ModerationController::action).toList());
    }

    private static ReportItem item(Report r) {
        return new ReportItem(r.id(), r.reporterId(), r.targetType(), r.targetId(), r.reason(), r.details(), r.createdAt());
    }

    private static ActionItem action(Action a) {
        return new ActionItem(a.id(), a.reportId(), a.actorId(), a.actionType(), a.targetType(), a.targetId(), a.reason(),
                a.occurredAt());
    }
}
