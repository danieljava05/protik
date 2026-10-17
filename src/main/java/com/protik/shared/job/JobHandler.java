package com.protik.shared.job;

/**
 * Runs jobs of one type. Register an implementation as a Spring bean.
 *
 * <p>Delivery is at-least-once (ADR 0008): a handler can run twice for the same
 * job, so it must be idempotent. Throwing any exception fails the attempt; the
 * job is retried with backoff until its attempts are used up.
 */
public interface JobHandler {

    /** The job type this handler serves. Exactly one handler may serve a type. */
    String type();

    void handle(JobContext context) throws Exception;
}
