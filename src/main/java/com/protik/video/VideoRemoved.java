package com.protik.video;

import java.util.UUID;

/**
 * A video must no longer be shown (taken down, deleted by its creator, or its account deleted).
 * Delivered at least once, so listeners must treat a repeat as a no-op.
 */
public record VideoRemoved(UUID videoId) {
}
