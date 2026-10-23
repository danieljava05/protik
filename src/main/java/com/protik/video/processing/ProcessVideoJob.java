package com.protik.video.processing;

import com.protik.shared.id.Ids;
import com.protik.shared.job.JobContext;
import com.protik.shared.job.JobHandler;
import com.protik.video.Rendition;
import com.protik.video.RenditionCatalog;
import com.protik.video.VideoPublished;
import com.protik.video.persistence.VideoRepository;
import com.protik.video.service.UploadProperties;
import com.protik.video.service.UploadService;
import com.protik.video.storage.ObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes a quarantined upload through scan, probe, transcode and publish (ADR 0022 decision 3). Delivery is at least
 * once, so every step can be repeated: a video that is no longer PROCESSING is left alone, assets are upserted, and
 * the final publish is one transaction.
 *
 * <p>A bad file is a permanent failure (the video becomes FAILED and the job succeeds); an unreachable scanner or a
 * storage error is a temporary one (the job throws and is retried), and the video stays unpublished meanwhile.
 */
@Component
class ProcessVideoJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ProcessVideoJob.class);
    private static final Duration LEASE = Duration.ofMinutes(10);

    private final VideoRepository videos;
    private final ObjectStorage storage;
    private final MalwareScanner scanner;
    private final MediaTools tools;
    private final RenditionCatalog renditions;
    private final UploadProperties upload;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    ProcessVideoJob(VideoRepository videos, ObjectStorage storage, MalwareScanner scanner, MediaTools tools,
            RenditionCatalog renditions, UploadProperties upload, ApplicationEventPublisher events,
            PlatformTransactionManager transactions, JsonMapper json) {
        this.videos = videos;
        this.storage = storage;
        this.scanner = scanner;
        this.tools = tools;
        this.renditions = renditions;
        this.upload = upload;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.json = json;
    }

    @Override
    public String type() {
        return UploadService.PROCESS_JOB;
    }

    @Override
    public void handle(JobContext context) throws Exception {
        UUID videoId = UUID.fromString(json.readTree(context.payloadJson()).path("videoId").asString(""));
        var row = transaction.execute(s -> videos.find(videoId).orElse(null));
        if (row == null || !"PROCESSING".equals(row.status())) {
            log.info("Video {} is not waiting for processing; nothing to do", videoId);
            return;
        }
        var original = transaction.execute(s -> videos.findAsset(videoId, "ORIGINAL", null).orElse(null));
        if (original == null) {
            fail(videoId, "The uploaded file is missing.");
            return;
        }

        // Gate 1: malware. No verdict means no publish.
        MalwareScanner.Result verdict;
        try (InputStream in = storage.open(original.storageKey())) {
            verdict = scanner.scan(in);
        }
        if (!verdict.clean()) {
            log.warn("Video {} rejected: malware signature {}", videoId, verdict.signature());
            transaction.executeWithoutResult(s -> {
                videos.setScan(videoId, "INFECTED");
                videos.markFailed(videoId, "The file was rejected by the virus scanner.");
                videos.deleteAsset(videoId, "ORIGINAL");
            });
            return;
        }
        String scanStatus = scanner.scans() ? "CLEAN" : "SKIPPED";
        transaction.executeWithoutResult(s -> videos.setScan(videoId, scanStatus));
        if (!context.extendLease(LEASE)) {
            return;
        }

        Path work = Files.createTempDirectory("protik-video-");
        List<String> written = new ArrayList<>();
        try {
            Path source = storage.localPath(original.storageKey());
            // Gate 2: probe.
            MediaTools.Probe probe = tools.probe(source, upload.maxDuration().toMillis());

            // Gate 3: transcode every rendition that does not enlarge the picture, plus audio only.
            List<Encoded> encoded = new ArrayList<>();
            List<Rendition> ladder = renditions.all();
            int lowestVideo = ladder.stream().filter(r -> r.heightPx() != null).mapToInt(Rendition::heightPx).min().orElse(0);
            for (Rendition rendition : ladder) {
                boolean audio = rendition.heightPx() == null;
                if (audio ? !probe.hasAudio() : (rendition.heightPx() > probe.height() && rendition.heightPx() != lowestVideo)) {
                    continue;
                }
                Path out = work.resolve(rendition.code() + ".mp4");
                tools.encode(source, out, rendition, probe);
                String key = "renditions/" + videoId + "/" + rendition.code() + ".mp4";
                long size = putFile(key, out);
                written.add(key);
                int kbps = (int) Math.max(1, size * 8 / Math.max(1, probe.durationMs()));
                encoded.add(new Encoded(rendition.code(), key, size, kbps));
                if (!context.extendLease(LEASE)) {
                    return;
                }
            }
            String thumbKey = null;
            long thumbSize = 0;
            try {
                Path thumb = work.resolve("thumb.jpg");
                tools.thumbnail(source, thumb, probe);
                thumbKey = "thumbs/" + videoId + ".jpg";
                thumbSize = putFile(thumbKey, thumb);
                written.add(thumbKey);
            } catch (MediaTools.RejectedMediaException e) {
                log.info("Video {} has no thumbnail: {}", videoId, e.getMessage());
            }

            // Gate 4: publish, atomically.
            publish(videoId, row, probe, encoded, thumbKey, thumbSize);
        } catch (MediaTools.RejectedMediaException e) {
            deleteAll(written);
            fail(videoId, e.getMessage());
        } finally {
            deleteTree(work);
        }
    }

    private record Encoded(String code, String key, long size, int kbps) {
    }

    private void publish(UUID videoId, com.protik.video.persistence.VideoRepository.Row row, MediaTools.Probe probe,
            List<Encoded> encoded, String thumbKey, long thumbSize) {
        transaction.executeWithoutResult(s -> {
            for (Encoded e : encoded) {
                videos.upsertAsset(Ids.newId(), videoId, "MP4_VARIANT", e.code(), e.key(), e.size(), e.kbps());
            }
            if (thumbKey != null) {
                videos.upsertAsset(Ids.newId(), videoId, "THUMBNAIL", null, thumbKey, thumbSize, null);
            }
            Instant now = Instant.now();
            if (videos.publish(videoId, probe.durationMs(), now)) {
                videos.deleteAsset(videoId, "ORIGINAL"); // the quarantined file is recorded as a tombstone and swept
                events.publishEvent(new VideoPublished(videoId, row.creatorId(), now, probe.durationMs(), row.language(),
                        videos.tags(videoId)));
            }
        });
    }

    private void fail(UUID videoId, String reason) {
        transaction.executeWithoutResult(s -> {
            videos.markFailed(videoId, reason);
            videos.deleteAsset(videoId, "ORIGINAL");
        });
    }

    private long putFile(String key, Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return storage.put(key, in, Long.MAX_VALUE);
        }
    }

    private void deleteAll(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not remove {} after a failed encode: {}", key, e.toString());
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not remove the work directory {}: {}", dir, e.toString());
        }
    }
}
