package com.protik.social;

import java.util.UUID;

/**
 * The number of visible comments on {@code videoId} changed (a comment or a thread was added or deleted).
 * It carries no number: listeners ask {@link CommentCounts} for the truth, which keeps repeats and reordering
 * harmless (ADR 0017). Delivered at least once, so listeners must be idempotent.
 */
public record CommentCountChanged(UUID videoId) {
}
