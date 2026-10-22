package com.protik.social;

import java.util.UUID;

/**
 * {@code userId} started following {@code creatorId}. Published only when the follow really was new
 * (following someone already followed publishes nothing), in the same transaction as the change.
 * Delivered at least once, so listeners must be idempotent.
 */
public record UserFollowed(UUID userId, UUID creatorId) {
}
