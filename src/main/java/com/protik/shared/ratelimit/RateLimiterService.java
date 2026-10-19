package com.protik.shared.ratelimit;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RateLimiterService implements RateLimiter {

    /** Rows whose window ended more than this long ago can no longer matter. */
    private static final Duration PURGE_AGE = Duration.ofDays(1);
    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final RateLimitStore store;

    RateLimiterService(RateLimitStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Duration> acquire(List<Limit> limits) {
        double longestWait = -1;
        // Always lock rows in key order, so two requests touching the same keys cannot deadlock.
        for (Limit limit : limits.stream().sorted(Comparator.comparing(Limit::key)).toList()) {
            var counter = store.increment(limit.key(), limit.window());
            if (counter.count() > limit.max()) {
                longestWait = Math.max(longestWait, counter.secondsLeft());
            }
        }
        if (longestWait < 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis((long) Math.ceil(Math.max(longestWait, 1) * 1000)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refund(List<String> keys) {
        keys.stream().sorted().forEach(store::decrement);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(List<String> keys) {
        store.delete(keys);
    }

    @Scheduled(fixedDelayString = "${protik.ratelimit.purge-interval:PT1H}",
            initialDelayString = "${protik.ratelimit.purge-interval:PT1H}")
    void purge() {
        int removed = store.purgeOlderThan(PURGE_AGE);
        if (removed > 0) {
            log.info("Purged {} old rate-limit window(s)", removed);
        }
    }
}
