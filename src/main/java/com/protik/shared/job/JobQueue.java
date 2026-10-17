package com.protik.shared.job;

import java.time.Duration;
import java.util.UUID;

/**
 * The one job queue (ADR 0003, ADR 0008). Modules enqueue work here; they
 * never build a second job system.
 *
 * <p>Enqueueing must happen inside the caller's transaction, so the job exists
 * if and only if the business change that needs it was committed. Calling it
 * without a transaction is an error.
 */
public interface JobQueue {

    int DEFAULT_MAX_ATTEMPTS = 5;

    /**
     * Enqueues a job to run as soon as a worker is free.
     *
     * <p>Deliberately not a default method delegating to the four-argument
     * overload: such a call would bypass the transaction proxy (Spring
     * self-invocation) and silently skip the "must be in a transaction" rule.
     */
    UUID enqueue(String type, String payloadJson);

    /**
     * @param type        which {@link JobHandler} runs it (1 to 100 characters)
     * @param payloadJson a valid JSON document; handlers must tolerate being run twice
     * @param delay       do not run before now + delay (database clock)
     * @param maxAttempts attempts before the job is parked as DEAD
     * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction is active
     */
    UUID enqueue(String type, String payloadJson, Duration delay, int maxAttempts);
}
