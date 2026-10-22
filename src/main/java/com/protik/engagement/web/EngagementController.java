package com.protik.engagement.web;

import com.protik.engagement.service.LikeService;
import com.protik.shared.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Likes. PUT and DELETE are idempotent by definition, so neither needs an {@code Idempotency-Key};
 * both answer 204 whether or not anything changed.
 */
@RestController
@RequestMapping("/v1/videos/{videoId}")
class EngagementController {

    record EngagementResponse(long likes, long comments, long views, boolean likedByMe) {
    }

    private final LikeService likes;

    EngagementController(LikeService likes) {
        this.likes = likes;
    }

    @PutMapping("/like")
    ResponseEntity<Void> like(@AuthenticationPrincipal Jwt token, @PathVariable UUID videoId) {
        likes.like(AuthenticatedUser.id(token), videoId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/like")
    ResponseEntity<Void> unlike(@AuthenticationPrincipal Jwt token, @PathVariable UUID videoId) {
        likes.unlike(AuthenticatedUser.id(token), videoId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** Counters (about a second behind the truth) and whether the caller has liked it (always current). */
    @GetMapping("/engagement")
    ResponseEntity<EngagementResponse> engagement(@AuthenticationPrincipal Jwt token, @PathVariable UUID videoId) {
        var e = likes.engagement(AuthenticatedUser.id(token), videoId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EngagementResponse(e.likes(), e.comments(), e.views(), e.likedByViewer()));
    }
}
