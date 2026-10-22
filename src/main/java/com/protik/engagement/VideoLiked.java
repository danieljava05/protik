package com.protik.engagement;

import java.util.UUID;

/**
 * {@code userId} liked {@code videoId}. Published only when the like really was new, in the same
 * transaction as the change. Delivered at least once, so listeners must be idempotent.
 */
public record VideoLiked(UUID videoId, UUID userId) {
}
