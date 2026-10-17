package com.protik.shared.job;

import java.time.Duration;
import java.util.UUID;

/** What a handler knows about the job it is running. */
public interface JobContext {

    UUID id();

    String type();

    /** The JSON payload exactly as enqueued. */
    String payloadJson();

    /** 1 on the first run, 2 on the first retry, and so on. */
    int attempt();

    /**
     * Pushes the lease out to now + duration (database clock). Long jobs call
     * this periodically so a healthy worker is not mistaken for a dead one.
     *
     * @return false if this worker has lost the job (its lease expired and the
     *         job was handed on); the handler should stop, its result will be discarded
     */
    boolean extendLease(Duration duration);
}
