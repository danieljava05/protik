package com.protik.video;

import java.util.UUID;

/** What the moderation module may do to a video (ADR 0019). Idempotent. */
public interface VideoModeration {

    /** Takes a published video down and announces {@link VideoRemoved}. @return false if it was not published */
    boolean takeDown(UUID videoId);
}
