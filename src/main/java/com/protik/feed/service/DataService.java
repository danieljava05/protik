package com.protik.feed.service;

import com.protik.feed.persistence.DataRepository;
import com.protik.feed.persistence.DataRepository.Settings;
import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.error.RateLimitedException;
import com.protik.shared.ratelimit.RateLimitKeys;
import com.protik.shared.ratelimit.RateLimiter;
import com.protik.video.Rendition;
import com.protik.video.RenditionCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A viewer's data settings, the usage they report, and the advice computed from both (ADR 0018). The server never
 * blocks playback: it tells the app what to do and trusts the app's own reports of how much it used.
 */
@Service
public class DataService {

    static final Settings DEFAULTS = new Settings(false, null, 5, 1);
    static final long BYTES_PER_MB = 1_000_000L;
    static final int MAX_BATCH = 100;
    static final long MAX_EVENT_BYTES = 1L << 30; // 1 GiB
    static final Duration MAX_EVENT_AGE = Duration.ofDays(40);
    static final Duration ALLOWED_CLOCK_SKEW = Duration.ofMinutes(5);
    static final int REPORTS_PER_MINUTE = 60;

    /** One reported download. The ID is chosen by the client so that a retry cannot count twice. */
    public record UsageEvent(UUID id, UUID videoId, String rendition, long bytes, Instant occurredAt) {
    }

    public record Summary(LocalDate periodStart, LocalDate periodEnd, long usedBytes, Long capBytes,
            Map<String, Long> byRendition, DataPolicy policy) {
    }

    private final DataRepository repository;
    private final RenditionCatalog renditions;
    private final RateLimiter limiter;
    private final Clock clock;

    DataService(DataRepository repository, RenditionCatalog renditions, RateLimiter limiter, Clock clock) {
        this.repository = repository;
        this.renditions = renditions;
        this.limiter = limiter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings settings(UUID userId) {
        return repository.findSettings(userId).orElse(DEFAULTS);
    }

    @Transactional
    public Settings saveSettings(UUID userId, boolean dataSaver, Integer monthlyCapMb, int prefetchCapMb, int billingDay) {
        if (monthlyCapMb != null && (monthlyCapMb < 1 || monthlyCapMb > 1_000_000)) {
            throw invalid("The monthly cap must be between 1 and 1,000,000 MB, or empty for no cap.");
        }
        if (prefetchCapMb < 0 || prefetchCapMb > 1_000) {
            throw invalid("The prefetch cap must be between 0 and 1,000 MB.");
        }
        if (billingDay < 1 || billingDay > 28) {
            throw invalid("The billing day must be between 1 and 28.");
        }
        Settings saved = new Settings(dataSaver, monthlyCapMb, prefetchCapMb, billingDay);
        repository.saveSettings(userId, saved);
        return saved;
    }

    /** The advice for this viewer right now. Two small indexed queries. */
    @Transactional(readOnly = true)
    public DataPolicy policy(UUID userId) {
        Settings settings = repository.findSettings(userId).orElse(DEFAULTS);
        BillingCycle cycle = cycleOf(settings);
        long used = repository.usageTotal(userId, cycle.start(), cycle.end());
        return compute(settings, used);
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID userId) {
        Settings settings = repository.findSettings(userId).orElse(DEFAULTS);
        BillingCycle cycle = cycleOf(settings);
        Map<String, Long> byRendition = repository.usageByRendition(userId, cycle.start(), cycle.end());
        long used = byRendition.values().stream().mapToLong(Long::longValue).sum();
        Long cap = settings.monthlyCapMb() == null ? null : settings.monthlyCapMb() * BYTES_PER_MB;
        return new Summary(cycle.startDate(), cycle.startDate().plusMonths(1), used, cap, byRendition,
                compute(settings, used));
    }

    /**
     * Counts a report against the user's limit. Call it before opening the transaction that records the events: the
     * limiter commits on its own and must not hold a second connection per request.
     */
    public void beginReport(UUID userId) {
        limiter.acquire(List.of(new RateLimiter.Limit(RateLimitKeys.of("usage-report", userId.toString()),
                        REPORTS_PER_MINUTE, Duration.ofMinutes(1))))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
    }

    /** @return how many events were new (repeated IDs are ignored, so a retried batch is harmless) */
    @Transactional
    public int report(UUID userId, List<UsageEvent> events) {
        if (events.isEmpty() || events.size() > MAX_BATCH) {
            throw invalid("Send between 1 and " + MAX_BATCH + " events per report.");
        }
        Set<String> known = renditions.all().stream().map(Rendition::code).collect(Collectors.toSet());
        Instant now = clock.instant();
        for (UsageEvent e : events) {
            if (e.id() == null || e.videoId() == null || e.occurredAt() == null || e.rendition() == null) {
                throw invalid("Every event needs an id, videoId, rendition and occurredAt.");
            }
            if (!known.contains(e.rendition())) {
                throw invalid("Unknown rendition: " + abbreviate(e.rendition()) + ".");
            }
            if (e.bytes() < 0 || e.bytes() > MAX_EVENT_BYTES) {
                throw invalid("bytes must be between 0 and " + MAX_EVENT_BYTES + ".");
            }
            if (e.occurredAt().isAfter(now.plus(ALLOWED_CLOCK_SKEW)) || e.occurredAt().isBefore(now.minus(MAX_EVENT_AGE))) {
                throw invalid("occurredAt must be within the last " + MAX_EVENT_AGE.toDays() + " days and not in the future.");
            }
        }
        int added = 0;
        for (UsageEvent e : events) {
            if (repository.insertUsage(e.id(), userId, e.videoId(), e.rendition(), e.bytes(), e.occurredAt())) {
                added++;
            }
        }
        return added;
    }

    private DataPolicy compute(Settings settings, long usedBytes) {
        Long cap = settings.monthlyCapMb() == null ? null : settings.monthlyCapMb() * BYTES_PER_MB;
        return DataPolicy.compute(settings.dataSaver(), cap, usedBytes, settings.prefetchCapMb() * BYTES_PER_MB);
    }

    private BillingCycle cycleOf(Settings settings) {
        return BillingCycle.containing(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), settings.billingDay());
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ProblemType.VALIDATION_FAILED, detail);
    }

    private static String abbreviate(String text) {
        return text.length() <= 30 ? text : text.substring(0, 30) + "...";
    }
}
