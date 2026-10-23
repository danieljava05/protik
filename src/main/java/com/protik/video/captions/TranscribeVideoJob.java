package com.protik.video.captions;

import com.protik.shared.job.JobContext;
import com.protik.shared.job.JobHandler;
import com.protik.video.persistence.VideoRepository;
import com.protik.video.storage.ObjectStorage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes machine captions for a published video whose language has a measured error rate (ADR 0023). It does nothing, and
 * says so in the log, when the language is not supported or no model is configured. Running twice replaces the same
 * caption, so redelivery is harmless.
 */
@Component
class TranscribeVideoJob implements JobHandler {

    static final String TYPE = "video.caption";
    private static final Logger log = LoggerFactory.getLogger(TranscribeVideoJob.class);

    private final VideoRepository videos;
    private final ObjectStorage storage;
    private final Transcriber transcriber;
    private final CaptionService captions;
    private final JsonMapper json;

    TranscribeVideoJob(VideoRepository videos, ObjectStorage storage, Transcriber transcriber, CaptionService captions,
            JsonMapper json) {
        this.videos = videos;
        this.storage = storage;
        this.transcriber = transcriber;
        this.captions = captions;
        this.json = json;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(JobContext context) throws Exception {
        UUID videoId = UUID.fromString(json.readTree(context.payloadJson()).path("videoId").asString(""));
        var row = videos.find(videoId).orElse(null);
        if (row == null || !"PUBLISHED".equals(row.status()) || row.language() == null) {
            log.info("Video {} cannot be captioned now; nothing to do", videoId);
            return;
        }
        if (!videos.asrSupported(row.language())) {
            log.info("No measured speech recognition for language {}; video {} gets no machine captions", row.language(), videoId);
            return;
        }
        if (!transcriber.available()) {
            log.warn("Language {} is marked supported but no transcriber is configured; video {} gets no machine captions",
                    row.language(), videoId);
            return;
        }
        var asset = videos.findAsset(videoId, "MP4_VARIANT", "audio")
                .or(() -> videos.findAsset(videoId, "MP4_VARIANT", "144p")).orElse(null);
        if (asset == null) {
            log.warn("Video {} has no audio to transcribe", videoId);
            return;
        }
        var transcript = transcriber.transcribe(storage.localPath(asset.storageKey()), row.language());
        if (transcript.cues().isEmpty()) {
            log.info("The transcriber found no speech in video {}", videoId);
            return;
        }
        captions.putMachineCaptions(videoId, row.language(), transcriber.model(), transcriber.modelVersion(),
                transcript.confidence(), transcript.cues());
    }
}
