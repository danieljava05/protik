package com.protik.feed.service;

import com.protik.feed.persistence.DataRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes usage rows older than 400 days (ADR 0018 decision 6): they are personal data and are not kept longer than a
 * year-on-year comparison needs. A single statement, so it needs no surrounding transaction.
 */
@Component
class UsagePurger {

    static final Duration RETENTION = Duration.ofDays(400);
    private static final Logger log = LoggerFactory.getLogger(UsagePurger.class);

    private final DataRepository repository;

    UsagePurger(DataRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${protik.feed.usage-purge-interval:PT24H}",
            initialDelayString = "${protik.feed.usage-purge-interval:PT24H}")
    void purge() {
        int removed = repository.purgeUsageOlderThan(RETENTION);
        if (removed > 0) {
            log.info("Purged {} usage event(s) older than {} days", removed, RETENTION.toDays());
        }
    }
}
