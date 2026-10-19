package com.protik.feed.web;

import com.protik.feed.service.FeedPreferencesService;
import com.protik.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ranking transparency and control (VISION.md goal 1): see the rules, pick a mode, adjust weights. */
@RestController
@RequestMapping("/v1/feed")
class FeedPreferencesController {

    record ModeResponse(String code, String description, boolean followedOnly, Map<String, BigDecimal> weights) {
    }

    record ComponentResponse(String code, String description, BigDecimal modeWeight, BigDecimal override,
            BigDecimal effectiveWeight) {
    }

    record PreferencesResponse(String mode, boolean followedOnly, List<ComponentResponse> components) {
    }

    record UpdateRequest(@NotBlank @Size(max = 40) String mode,
            @Size(max = 20) Map<@Size(max = 40) String, BigDecimal> weights) {
    }

    private final FeedPreferencesService preferences;

    FeedPreferencesController(FeedPreferencesService preferences) {
        this.preferences = preferences;
    }

    /** Every ranking mode and exactly which weights it applies. */
    @GetMapping("/modes")
    List<ModeResponse> modes() {
        return preferences.modes().stream()
                .map(m -> new ModeResponse(m.code(), m.description(), m.followedOnly(), m.weights())).toList();
    }

    @GetMapping("/preferences")
    ResponseEntity<PreferencesResponse> get(@AuthenticationPrincipal Jwt token) {
        return respond(preferences.preferences(AuthenticatedUser.id(token)));
    }

    /**
     * Replaces the caller's mode and weight overrides. Idempotent by nature (a PUT), so it needs no
     * Idempotency-Key. Omit {@code weights} or send {@code {}} to use the mode exactly as published.
     */
    @PutMapping("/preferences")
    ResponseEntity<PreferencesResponse> put(@AuthenticationPrincipal Jwt token,
            @Valid @RequestBody UpdateRequest request) {
        UUID userId = AuthenticatedUser.id(token);
        Map<String, BigDecimal> overrides = request.weights() == null ? Map.of() : request.weights();
        return respond(preferences.save(userId, request.mode(), overrides));
    }

    private static ResponseEntity<PreferencesResponse> respond(FeedPreferencesService.Preferences p) {
        var components = p.components().stream().map(c -> new ComponentResponse(c.code(), c.description(),
                c.modeWeight(), c.override(), c.effectiveWeight())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PreferencesResponse(p.mode(), p.followedOnly(), components));
    }
}
