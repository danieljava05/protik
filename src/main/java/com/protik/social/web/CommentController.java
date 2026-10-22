package com.protik.social.web;

import com.protik.shared.idempotency.IdempotencyService;
import com.protik.shared.idempotency.IdempotentAction;
import com.protik.shared.security.AuthenticatedUser;
import com.protik.social.service.CommentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Comments (ADR 0017). Text is plain: the server stores it as given and never treats it as markup, so a
 * client must escape it when displaying.
 */
@RestController
class CommentController {

    record CreateRequest(@NotNull String body, UUID parentId) {
    }

    record CommentResponse(UUID id, UUID videoId, UUID authorId, UUID parentId, String body, Instant createdAt) {
    }

    record Item(UUID id, UUID authorId, String body, Instant createdAt, long replyCount) {
    }

    record PageResponse(List<Item> items, String nextCursor) {
    }

    private final CommentService comments;
    private final IdempotencyService idempotency;

    CommentController(CommentService comments, IdempotencyService idempotency) {
        this.comments = comments;
        this.idempotency = idempotency;
    }

    /** Posts a comment (or a reply, with {@code parentId}). Supports {@code Idempotency-Key}. */
    @PostMapping("/v1/videos/{videoId}/comments")
    ResponseEntity<CommentResponse> post(@AuthenticationPrincipal Jwt token, @PathVariable UUID videoId,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateRequest request) {
        UUID userId = AuthenticatedUser.id(token);
        // Before the idempotency transaction, so a blocked request holds no database connection.
        comments.beginPosting(userId);
        // The path is part of the endpoint label, so one key cannot be replayed onto a different video.
        return idempotency.execute(userId, idempotencyKey, "POST /v1/videos/" + videoId + "/comments", request,
                        CommentResponse.class, () -> {
                            var saved = comments.post(userId, videoId, request.body(), request.parentId());
                            return new IdempotentAction.Outcome<>(201, new CommentResponse(saved.id(), saved.videoId(),
                                    saved.authorId(), saved.parentId(), saved.body(), saved.createdAt()));
                        })
                .toResponseEntity();
    }

    /** Top-level comments, newest first. */
    @GetMapping("/v1/videos/{videoId}/comments")
    ResponseEntity<PageResponse> list(@PathVariable UUID videoId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return respond(comments.topLevel(videoId, limit, cursor));
    }

    /** Replies to one comment, oldest first. */
    @GetMapping("/v1/comments/{commentId}/replies")
    ResponseEntity<PageResponse> replies(@PathVariable UUID commentId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return respond(comments.replies(commentId, limit, cursor));
    }

    /** Deletes the caller's own comment, and the replies under it. Deleting what is already gone is a 404. */
    @DeleteMapping("/v1/comments/{commentId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt token, @PathVariable UUID commentId) {
        comments.delete(AuthenticatedUser.id(token), commentId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<PageResponse> respond(CommentService.Page page) {
        var items = page.items().stream()
                .map(c -> new Item(c.id(), c.authorId(), c.body(), c.createdAt(), c.replyCount())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageResponse(items, page.nextCursor()));
    }
}
