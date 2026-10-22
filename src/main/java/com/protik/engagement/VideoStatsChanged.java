package com.protik.engagement;

import java.time.Instant;
import java.util.UUID;

/**
 * The counters of a video, recounted from the truth. They are absolute values, not changes, so
 * delivering the same event twice is harmless. Events can arrive out of order, so each carries the
 * database time it was computed at, and a listener must ignore an event older than what it has
 * already applied (ADR 0016).
 */
public record VideoStatsChanged(UUID videoId, long likes, long comments, long views, Instant computedAt) {
}
