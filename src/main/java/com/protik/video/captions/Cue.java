package com.protik.video.captions;

/** One timed line of a transcript. Times are milliseconds from the start of the video. */
public record Cue(int startMs, int endMs, String text) {

    public Cue {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("a cue must end after it starts, and not start before zero");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a cue needs text");
        }
    }
}
