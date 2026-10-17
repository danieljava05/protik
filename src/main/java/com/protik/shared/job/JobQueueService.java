package com.protik.shared.job;

import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class JobQueueService implements JobQueue {

    private final JobStore store;

    JobQueueService(JobStore store) {
        this.store = store;
    }

    /** MANDATORY: the job is created atomically with the caller's business change. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String type, String payloadJson) {
        return doEnqueue(type, payloadJson, Duration.ZERO, DEFAULT_MAX_ATTEMPTS);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String type, String payloadJson, Duration delay, int maxAttempts) {
        return doEnqueue(type, payloadJson, delay, maxAttempts);
    }

    private UUID doEnqueue(String type, String payloadJson, Duration delay, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return store.enqueue(type, payloadJson, delay, maxAttempts);
    }
}
