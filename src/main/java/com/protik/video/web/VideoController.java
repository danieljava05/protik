package com.protik.video.web;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;
import com.protik.shared.idempotency.IdempotencyService;
import com.protik.shared.idempotency.IdempotentAction;
import com.protik.shared.security.AuthenticatedUser;
import com.protik.video.persistence.VideoRepository;
import com.protik.video.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import com.protik.video.storage.ObjectStorage;
import java.time.Duration;

/** Creating a video, sending its file, checking on it, and fetching what was made (ADR 0022). */
@RestController
class VideoController {

    record CreateRequest(@Size(max = 2200) String description, @Size(max = 8) String language) {
    }

    record Created(UUID id, String status) {
    }

    record RenditionInfo(String code, long sizeBytes, Integer avgBitrateKbps) {
    }

    record VideoResponse(UUID id, String status, String description, String language, Integer durationMs,
            String failureReason, List<RenditionInfo> renditions) {
    }

    private final UploadService uploads;
    private final VideoRepository videos;
    private final ObjectStorage storage;
    private final IdempotencyService idempotency;

    VideoController(UploadService uploads, VideoRepository videos, ObjectStorage storage, IdempotencyService idempotency) {
        this.uploads = uploads;
        this.videos = videos;
        this.storage = storage;
        this.idempotency = idempotency;
    }

    /** Starts an upload. Supports {@code Idempotency-Key}, so a retry does not create a second video. */
    @PostMapping("/v1/videos")
    ResponseEntity<Created> create(@AuthenticationPrincipal Jwt token,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateRequest request) {
        UUID userId = AuthenticatedUser.id(token);
        uploads.beginCreate(userId);
        return idempotency.execute(userId, idempotencyKey, "POST /v1/videos", request, Created.class, () -> {
            UUID id = uploads.create(userId, request.description(), request.language());
            return new IdempotentAction.Outcome<>(201, new Created(id, "UPLOADING"));
        }).toResponseEntity();
    }

    /** The file itself, as the request body. 202: it is queued for scanning and processing, not yet watchable. */
    @PutMapping("/v1/videos/{id}/content")
    ResponseEntity<Created> content(@AuthenticationPrincipal Jwt token, @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType, HttpServletRequest http)
            throws IOException {
        uploads.receive(AuthenticatedUser.id(token), id, contentType, http.getInputStream());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new Created(id, "PROCESSING"));
    }

    /** The owner sees the state at every step; everyone else sees only published videos. */
    @GetMapping("/v1/videos/{id}")
    @Transactional(readOnly = true)
    ResponseEntity<VideoResponse> get(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        UUID userId = AuthenticatedUser.id(token);
        var details = videos.details(id).orElseThrow(VideoController::notFound);
        boolean owner = details.row().creatorId().equals(userId);
        if (!owner && !"PUBLISHED".equals(details.row().status())) {
            throw notFound();
        }
        var renditions = videos.variants(id).stream()
                .map(v -> new RenditionInfo(v.code(), v.sizeBytes(), v.avgKbps())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new VideoResponse(id, details.row().status(), details.description(), details.row().language(),
                        details.durationMs(), owner ? details.failureReason() : null, renditions));
    }

    /** One rendition of a published video, with HTTP Range support. */
    @GetMapping("/v1/videos/{id}/renditions/{code}")
    ResponseEntity<Resource> rendition(@PathVariable UUID id, @PathVariable String code) throws IOException {
        requirePublished(id);
        var asset = videos.findAsset(id, "MP4_VARIANT", code).orElseThrow(VideoController::notFound);
        MediaType type = code.equals("audio") ? MediaType.parseMediaType("audio/mp4") : MediaType.parseMediaType("video/mp4");
        return file(asset.storageKey(), type);
    }

    @GetMapping("/v1/videos/{id}/thumbnail")
    ResponseEntity<Resource> thumbnail(@PathVariable UUID id) throws IOException {
        requirePublished(id);
        var asset = videos.findAsset(id, "THUMBNAIL", null).orElseThrow(VideoController::notFound);
        return file(asset.storageKey(), MediaType.IMAGE_JPEG);
    }

    private ResponseEntity<Resource> file(String key, MediaType type) throws IOException {
        Resource resource = new FileSystemResource(storage.localPath(key));
        return ResponseEntity.ok().contentType(type)
                .header("X-Content-Type-Options", "nosniff")
                // A published file never changes (its key is fixed and re-processing creates new assets).
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(resource);
    }

    private void requirePublished(UUID id) {
        var row = videos.find(id).orElseThrow(VideoController::notFound);
        if (!"PUBLISHED".equals(row.status())) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(ProblemType.NOT_FOUND, "Video not found.");
    }
}
