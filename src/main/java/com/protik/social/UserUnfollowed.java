package com.protik.social;

import java.util.UUID;

/** {@code userId} stopped following {@code creatorId}. Published only when a follow was really removed. */
public record UserUnfollowed(UUID userId, UUID creatorId) {
}
