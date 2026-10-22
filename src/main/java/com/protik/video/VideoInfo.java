package com.protik.video;

import java.util.UUID;

/** What other modules may know about a video: who made it and whether people can see it. */
public record VideoInfo(UUID videoId, UUID creatorId, boolean published) {
}
