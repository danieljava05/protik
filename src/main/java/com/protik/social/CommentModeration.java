package com.protik.social;

import java.util.Optional;
import java.util.UUID;

/** What the moderation module may do to a comment (ADR 0019). Idempotent. */
public interface CommentModeration {

    /** @return true if a comment with this ID exists in any state */
    boolean exists(UUID commentId);

    /** @return the comment's author, if it exists (for "you cannot report yourself") */
    Optional<UUID> authorOf(UUID commentId);

    /** Hides a visible comment and its replies from readers and counts. @return false if it was not visible */
    boolean hide(UUID commentId);
}
