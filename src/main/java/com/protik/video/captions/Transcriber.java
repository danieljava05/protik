package com.protik.video.captions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns speech into timed text (ADR 0023 decision 3). A port: no model is bundled, because a model may be offered for a
 * language only after its word error rate has been measured with the protocol of ADR 0004, and none has been.
 */
public interface Transcriber {

    /** @param confidence the model's average confidence between 0 and 1, or null if it reports none */
    record Transcript(List<Cue> cues, Double confidence) {
    }

    /** False if no model is configured; the transcription job then does nothing. */
    boolean available();

    /** The model's name and version, recorded with every caption so scores can be compared across models. */
    String model();

    String modelVersion();

    /** @param audio an MP4 holding the video's audio (or a low-quality video with audio) */
    Transcript transcribe(Path audio, String language) throws IOException;
}
