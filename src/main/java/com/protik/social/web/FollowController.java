package com.protik.social.web;

import com.protik.shared.security.AuthenticatedUser;
import com.protik.social.service.FollowService;
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
 * The caller's follow relationship with another user. PUT and DELETE are idempotent by definition,
 * so neither needs an {@code Idempotency-Key}; both answer 204 whether or not anything changed.
 */
@RestController
@RequestMapping("/v1/users/{userId}/follow")
class FollowController {

    record Relationship(boolean following) {
    }

    private final FollowService follows;

    FollowController(FollowService follows) {
        this.follows = follows;
    }

    @PutMapping
    ResponseEntity<Void> follow(@AuthenticationPrincipal Jwt token, @PathVariable UUID userId) {
        follows.follow(AuthenticatedUser.id(token), userId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping
    ResponseEntity<Void> unfollow(@AuthenticationPrincipal Jwt token, @PathVariable UUID userId) {
        follows.unfollow(AuthenticatedUser.id(token), userId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping
    ResponseEntity<Relationship> get(@AuthenticationPrincipal Jwt token, @PathVariable UUID userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Relationship(follows.isFollowing(AuthenticatedUser.id(token), userId)));
    }
}
