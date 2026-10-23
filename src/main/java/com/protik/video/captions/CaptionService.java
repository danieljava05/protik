package com.protik.video.captions;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.error.RateLimitedException;
import com.protik.shared.id.Ids;
import com.protik.shared.ratelimit.RateLimitKeys;
import com.protik.shared.ratelimit.RateLimiter;
import com.protik.video.persistence.VideoRepository;
import com.protik.video.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Captions written by the creator, captions made by a machine, and transcript search (ADR 0023). */
@Service
public class CaptionService {

    public record Search(List<Match> matches) {
    }

    public record Match(UUID videoId, String language, String source, int startMs, String text) {
    }

    static final int MIN_QUERY = 2;
    static final int MAX_QUERY = 100;
    static final int MAX_RESULTS = 50;
    static final int SEARCHES_PER_MINUTE = 60;
    private static final Logger log = LoggerFactory.getLogger(CaptionService.class);

    private final CaptionRepository captions;
    private final VideoRepository videos;
    private final ObjectStorage storage;
    private final RateLimiter limiter;

    CaptionService(CaptionRepository captions, VideoRepository videos, ObjectStorage storage, RateLimiter limiter) {
        this.captions = captions;
        this.videos = videos;
        this.storage = storage;
        this.limiter = limiter;
    }

    /** The creator uploads their own captions, replacing any earlier ones in this language. */
    @Transactional
    public UUID putCreatorCaptions(UUID userId, UUID videoId, String language, byte[] body) {
        var details = videos.details(videoId).orElse(null);
        if (details == null || !details.row().creatorId().equals(userId)) {
            throw new ApiException(ProblemType.NOT_FOUND, "Video not found.");
        }
        if (details.row().status().equals("FAILED") || details.row().status().equals("TAKEN_DOWN")) {
            throw new ApiException(ProblemType.CONFLICT, "Captions cannot be added to this video.");
        }
        if (!captions.languageExists(language)) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "Unknown language code.");
        }
        if (body.length == 0 || body.length > WebVtt.MAX_BYTES) {
            throw new ApiException(body.length == 0 ? ProblemType.VALIDATION_FAILED : ProblemType.PAYLOAD_TOO_LARGE,
                    "Captions must be between 1 byte and " + WebVtt.MAX_BYTES + " bytes.");
        }
        List<Cue> cues;
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            cues = WebVtt.parse(decoder.decode(java.nio.ByteBuffer.wrap(body)).toString(), details.durationMs());
        } catch (CharacterCodingException e) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "The captions must be UTF-8 text.");
        } catch (WebVtt.InvalidCaptionsException e) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, e.getMessage());
        }
        return store(videoId, language, "CREATOR", null, null, null, cues);
    }

    /** Stores a machine-made caption (used by the transcription job). */
    @Transactional
    public UUID putMachineCaptions(UUID videoId, String language, String model, String modelVersion, Double confidence,
            List<Cue> cues) {
        return store(videoId, language, "ASR", model, modelVersion, confidence, cues);
    }

    private UUID store(UUID videoId, String language, String source, String model, String modelVersion, Double confidence,
            List<Cue> cues) {
        UUID id = Ids.newId();
        String key = "captions/" + id + ".vtt";
        byte[] vtt = WebVtt.write(cues).getBytes(StandardCharsets.UTF_8);
        try {
            storage.put(key, new ByteArrayInputStream(vtt), WebVtt.MAX_BYTES * 2L);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the captions file", e);
        }
        try {
            captions.replace(id, videoId, language, source, model, modelVersion, confidence, key, cues);
        } catch (RuntimeException e) {
            try {
                storage.delete(key);
            } catch (IOException | RuntimeException cleanup) {
                log.warn("Could not remove {} after a failed save: {}", key, cleanup.toString());
            }
            throw e;
        }
        return id;
    }

    /** The WebVTT to show for a published video (the owner may also read it before publication). */
    @Transactional(readOnly = true)
    public byte[] vtt(UUID requester, UUID videoId, String language) throws IOException {
        var row = videos.find(videoId).orElse(null);
        boolean visible = row != null && (row.status().equals("PUBLISHED") || row.creatorId().equals(requester));
        if (!visible) {
            throw new ApiException(ProblemType.NOT_FOUND, "Captions not found.");
        }
        String key = captions.vttKey(videoId, language)
                .orElseThrow(() -> new ApiException(ProblemType.NOT_FOUND, "Captions not found."));
        try (var in = storage.open(key)) {
            return in.readAllBytes();
        }
    }

    @Transactional(readOnly = true)
    public List<CaptionRepository.Listed> list(UUID requester, UUID videoId) {
        var row = videos.find(videoId).orElse(null);
        boolean visible = row != null && (row.status().equals("PUBLISHED") || row.creatorId().equals(requester));
        if (!visible) {
            throw new ApiException(ProblemType.NOT_FOUND, "Video not found.");
        }
        return captions.list(videoId);
    }

    /** Counts a search against the person's limit. Call before opening the transaction (the limiter commits itself). */
    public void beginSearch(UUID userId) {
        limiter.acquire(List.of(new RateLimiter.Limit(RateLimitKeys.of("search-user", userId.toString()),
                        SEARCHES_PER_MINUTE, Duration.ofMinutes(1))))
                .ifPresent(wait -> {
                    throw new RateLimitedException(wait);
                });
    }

    @Transactional(readOnly = true)
    public Search search(String query, String language, Integer limit) {
        String q = query == null ? "" : query.strip();
        if (q.length() < MIN_QUERY || q.length() > MAX_QUERY) {
            throw new ApiException(ProblemType.VALIDATION_FAILED,
                    "The search must be " + MIN_QUERY + " to " + MAX_QUERY + " characters.");
        }
        if (q.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "The search must not contain control characters.");
        }
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > MAX_RESULTS) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "limit must be between 1 and " + MAX_RESULTS + ".");
        }
        if (language != null && !captions.languageExists(language)) {
            throw new ApiException(ProblemType.VALIDATION_FAILED, "Unknown language code.");
        }
        return new Search(captions.search(q, language, size).stream()
                .map(h -> new Match(h.videoId(), h.language(), h.source(), h.startMs(), h.text())).toList());
    }
}
