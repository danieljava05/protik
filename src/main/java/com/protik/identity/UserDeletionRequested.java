package com.protik.identity;

import java.util.UUID;

/**
 * A person asked to have their account deleted (ADR 0020). Every module that holds personal data deletes
 * what it holds about {@code userId} and then calls {@link DeletionAcknowledgements#acknowledge}. Delivered at
 * least once, and republished until every module has acknowledged, so a listener must treat a repeat as a no-op.
 */
public record UserDeletionRequested(UUID userId, UUID requestId) {
}
