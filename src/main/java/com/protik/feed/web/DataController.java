package com.protik.feed.web;

import com.protik.feed.persistence.DataRepository.Settings;
import com.protik.feed.service.DataPolicy;
import com.protik.feed.service.DataService;
import com.protik.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Data budget and usage (ADR 0018). The server advises and never blocks playback; usage is reported by the app.
 */
@RestController
@RequestMapping("/v1/data")
class DataController {

/** Fields left out take their defaults: saver off, no cap, 5 MB preload, plan renews on the 1st. */
    record SettingsBody(Boolean dataSaver, Integer monthlyCapMb, Integer prefetchCapMb, Integer billingDay) {
    }

    record EventBody(UUID id, UUID videoId, String rendition, long bytes, Instant occurredAt) {
    }

    record ReportBody(@NotEmpty @Size(max = 100) List<@Valid EventBody> events) {
    }

    record ReportResult(int accepted, int ignoredDuplicates) {
    }

    record SummaryResponse(LocalDate periodStart, LocalDate periodEnd, long usedBytes, Long capBytes,
            Map<String, Long> byRendition, DataPolicy policy) {
    }

    private final DataService data;

    DataController(DataService data) {
        this.data = data;
    }

    @GetMapping("/settings")
    ResponseEntity<SettingsBody> settings(@AuthenticationPrincipal Jwt token) {
        return ok(toBody(data.settings(AuthenticatedUser.id(token))));
    }

    /** Replaces the data settings (a PUT, so repeating it is harmless). The ranking mode is not affected. */
    @PutMapping("/settings")
    ResponseEntity<SettingsBody> save(@AuthenticationPrincipal Jwt token, @RequestBody SettingsBody body) {
        return ok(toBody(data.saveSettings(AuthenticatedUser.id(token),
                body.dataSaver() != null && body.dataSaver(), body.monthlyCapMb(),
                body.prefetchCapMb() == null ? 5 : body.prefetchCapMb(),
                body.billingDay() == null ? 1 : body.billingDay())));
    }

    /**
     * Reports bytes the app downloaded. Every event has an ID chosen by the client, so resending a batch after a dropped
     * connection counts nothing twice and needs no {@code Idempotency-Key}.
     */
    @PostMapping("/usage")
    ResponseEntity<ReportResult> report(@AuthenticationPrincipal Jwt token, @Valid @RequestBody ReportBody body) {
        UUID userId = AuthenticatedUser.id(token);
        data.beginReport(userId);
        var events = body.events().stream()
                .map(e -> new DataService.UsageEvent(e.id(), e.videoId(), e.rendition(), e.bytes(), e.occurredAt())).toList();
        int accepted = data.report(userId, events);
        return ok(new ReportResult(accepted, events.size() - accepted));
    }

    /** What has been used this billing period, the cap, and what the app should do about it. */
    @GetMapping("/usage")
    ResponseEntity<SummaryResponse> summary(@AuthenticationPrincipal Jwt token) {
        var s = data.summary(AuthenticatedUser.id(token));
        return ok(new SummaryResponse(s.periodStart(), s.periodEnd(), s.usedBytes(), s.capBytes(), s.byRendition(), s.policy()));
    }

    private static SettingsBody toBody(Settings s) {
        return new SettingsBody(s.dataSaver(), s.monthlyCapMb(), s.prefetchCapMb(), s.billingDay());
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
