package com.protik.video.service;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.error.RateLimitedException;
import com.protik.shared.id.Ids;
import com.protik.shared.job.JobQueue;
import com.protik.shared.ratelimit.RateLimitKeys;
import com.protik.shared.ratelimit.RateLimiter;
import com.protik.video.persistence.VideoRepository;
import com.protik.video.storage.ObjectStorage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Creating a video and receiving its file (ADR 0022 decision 2). */
@Service
public class UploadService {

    public static final String PROCESS_JOB = "video.process";
    static final int MAX_DESCRIPTION = 2200;
    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final VideoRepository videos;
    private final ObjectStorage storage;
    private final JobQueue jobs;
    private final RateLimiter limiter;
    private final UploadProperties props;
    private final TransactionTemplate transaction;

    UploadService(VideoRepository videos, ObjectStorage storage, JobQueue jobs, RateLimiter limiter, UploadProperties props,
            PlatformTransactionManager transactions) {
        this.videos = videos;
        this.storage = storage;
        this.jobs = jobs;
        this.limiter = limiter;
        this.props = props;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Counts a start against the person's limit. Call before opening the transaction (the limiter commits itself). */
    public void beginCreate(UUID userId) {
        limiter.acquire(List.of(new RateLimiter.Limit(RateLimitKeys.of("upload-start", userId.toString()), props.perHour(),
                        Duration.ofHours(1))))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
    }

    @Transactional
    public UUID create(UUID userId, String description, String language) {
        String text = description == null ? "" : description.strip();
        if (text.length() > MAX_DESCRIPTION) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "The description must be at most " + MAX_DESCRIPTION + " characters.");
        }
        if (language != null && !videos.languageExists(language)) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "Unknown language code.");
        }
        UUID id = Ids.newId();
        videos.insertUploading(id, userId, text, language);
        return id;
    }

    /**
     * Streams the file into quarantine and queues processing. The body is never held in memory and never trusted: its
     * signature must match the declared type, and the byte count is capped while writing.
     */
    public void receive(UUID userId, UUID videoId, String contentType, InputStream body) {
        var declared = ContainerSniffer.declared(contentType);
        if (declared == null) {
            throw new ApiException(ProblemType.UNSUPPORTED_MEDIA, "Send video/mp4, video/quicktime or video/webm.");
        }
        // A cheap look first, so a wrong person or a finished upload does not cost a transfer.
        requireOpenUpload(userId, videoRepositoryView(videoId));

        String key = "quarantine/" + videoId + "-" + Ids.newId();
        try {
            var buffered = new BufferedInputStream(body, 64 * 1024);
            buffered.mark(ContainerSniffer.NEEDED);
            byte[] head = new byte[ContainerSniffer.NEEDED];
            int read = buffered.readNBytes(head, 0, head.length);
            buffered.reset();
            if (ContainerSniffer.sniff(head, read) != declared) {
                throw new ApiException(ProblemType.UNSUPPORTED_MEDIA, "The file is not the video type it claims to be.");
            }
            long size = storage.put(key, buffered, props.maxBytes());
            if (size == 0) {
                throw new ApiException(ProblemType.VALIDATION_FAILED, "The file is empty.");
            }
            try {
                transaction.executeWithoutResult(s -> accept(userId, videoId, key, size));
            } catch (RuntimeException e) {
                deleteQuietly(key);
                throw e;
            }
        } catch (ObjectStorage.TooLargeException e) {
            throw new ApiException(ProblemType.PAYLOAD_TOO_LARGE, "The video is larger than " + props.maxBytes() + " bytes.");
        } catch (ApiException e) {
            deleteQuietly(key);
            throw e;
        } catch (IOException e) {
            deleteQuietly(key);
            throw new ApiException(ProblemType.VALIDATION_FAILED, "The upload was interrupted. Send the file again.");
        }
    }

    private void accept(UUID userId, UUID videoId, String key, long size) {
        var row = videos.lock(videoId).orElse(null);
        requireOpenUpload(userId, row);
        videos.upsertAsset(Ids.newId(), videoId, "ORIGINAL", null, key, size, null);
        videos.markProcessing(videoId);
        jobs.enqueue(PROCESS_JOB, "{\"videoId\":\"" + videoId + "\"}");
    }

    private VideoRepository.Row videoRepositoryView(UUID videoId) {
        return transaction.execute(s -> videos.find(videoId).orElse(null));
    }

    /** Someone else's video looks exactly like a missing one. */
    private static void requireOpenUpload(UUID userId, VideoRepository.Row row) {
        if (row == null || !row.creatorId().equals(userId)) {
            throw new ApiException(ProblemType.NOT_FOUND, "Video not found.");
        }
        if (!"UPLOADING".equals(row.status())) {
            throw new ApiException(ProblemType.CONFLICT, "This video already has its file.");
        }
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not remove a rejected upload {}: {}", key, e.toString());
        }
    }
}
