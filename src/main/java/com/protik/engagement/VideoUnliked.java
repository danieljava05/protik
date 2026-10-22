package com.protik.engagement;

import java.util.UUID;

/** {@code userId} removed their like from {@code videoId}. Published only when a like was really removed. */
public record VideoUnliked(UUID videoId, UUID userId) {
}
