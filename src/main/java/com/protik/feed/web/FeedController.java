package com.protik.feed.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.protik.feed.ComponentScore;
import com.protik.feed.service.FeedService;
import com.protik.shared.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/feed")
class FeedController {

    record ExplanationLine(String component, double value, double weight, double contribution) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Item(UUID videoId, UUID creatorId, Instant publishedAt, int durationMs, String language, double score,
            List<ExplanationLine> explanation) {
    }

    record FeedResponse(Instant asOf, String mode, List<Item> items, String nextCursor) {
    }

    private final FeedService feed;

    FeedController(FeedService feed) {
        this.feed = feed;
    }

    /**
     * The viewer's feed, best first, each video with the exact score terms that put it there.
     *
     * @param limit   items per page (default 20, maximum 50)
     * @param cursor  {@code nextCursor} of the previous page; omit to start a fresh feed
     * @param explain set false to leave out the explanation and save bytes on a metered connection
     */
    @GetMapping
    ResponseEntity<FeedResponse> feed(@AuthenticationPrincipal Jwt token,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "true") boolean explain) {
        var page = feed.page(AuthenticatedUser.id(token), limit, cursor);
        List<Item> items = page.items().stream().map(entry -> {
            var candidate = entry.video().candidate();
            List<ExplanationLine> lines = explain
                    ? entry.ranked().components().stream().map(FeedController::line).toList() : null;
            return new Item(entry.ranked().videoId(), candidate.creatorId(), candidate.publishedAt(),
                    entry.video().durationMs(), entry.video().language(), entry.ranked().score(), lines);
        }).toList();
        // personalised and time-dependent: never cache, in the browser or in a shared proxy
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new FeedResponse(page.asOf(), page.mode(), items, page.nextCursor()));
    }

    private static ExplanationLine line(ComponentScore c) {
        return new ExplanationLine(c.code(), c.value(), c.weight(), c.contribution());
    }
}
