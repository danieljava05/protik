package com.protik.engagement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the counter work on a schedule (ADR 0016).
 *
 * <p>Deliberately a separate bean from {@link CounterService}: a scheduled method that called
 * {@code refresh()} on its own object would skip the transaction proxy. Counters would still be saved, but
 * without a surrounding transaction the events announcing them would never be delivered and a crash could
 * lose the work. Calling another bean always goes through the proxy.
 */
@Component
class CounterRefresher {

    private static final Logger log = LoggerFactory.getLogger(CounterRefresher.class);

    private final CounterService counters;

    CounterRefresher(CounterService counters) {
        this.counters = counters;
    }

    @Scheduled(fixedDelayString = "${protik.engagement.refresh-interval:PT1S}",
            initialDelayString = "${protik.engagement.refresh-interval:PT1S}")
    void refresh() {
        try {
            counters.refresh();
        } catch (RuntimeException e) {
            log.error("Counter refresh failed; the videos stay dirty and will be retried", e);
        }
    }

    @Scheduled(fixedDelayString = "${protik.engagement.reconcile-interval:PT1H}",
            initialDelayString = "${protik.engagement.reconcile-interval:PT1H}")
    void reconcile() {
        try {
            counters.reconcileAll();
        } catch (RuntimeException e) {
            log.error("Counter reconciliation failed", e);
        }
    }
}
