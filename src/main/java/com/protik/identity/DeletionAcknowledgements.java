package com.protik.identity;

import java.util.UUID;

/** How a module tells identity that it has deleted what it held about a user (ADR 0020). */
public interface DeletionAcknowledgements {

    /**
     * Records that {@code module} is done. Idempotent. When the last required module acknowledges, identity
     * deletes the account itself. Runs in the caller's transaction, so a module's deletion and its
     * acknowledgement commit together.
     */
    void acknowledge(UUID requestId, String module);
}
