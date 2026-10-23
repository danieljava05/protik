package com.protik.video.captions;

import com.protik.shared.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Captions and transcript search (ADR 0023). Caption and search text is plain text: clients must escape it. */
@RestController
class CaptionController {

    record Created(UUID id, String language, String source) {
    }

    record CaptionInfo(String language, String source, String status) {
    }

    record SearchResponse(List<CaptionService.Match> matches) {
    }

    private final CaptionService captions;

    CaptionController(CaptionService captions) {
        this.captions = captions;
    }

    /** The creator's own captions as a WebVTT request body. Replaces earlier captions in the language. */
    @PutMapping("/v1/videos/{id}/captions/{language}")
    ResponseEntity<Created> put(@AuthenticationPrincipal Jwt token, @PathVariable UUID id, @PathVariable String language,
            HttpServletRequest http) throws IOException {
        // Read at most one byte more than allowed, so an oversized body is refused without being held in memory.
        byte[] body = http.getInputStream().readNBytes(WebVtt.MAX_BYTES + 1);
        UUID captionId = captions.putCreatorCaptions(AuthenticatedUser.id(token), id, language, body);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new Created(captionId, language, "CREATOR"));
    }

    @GetMapping("/v1/videos/{id}/captions")
    ResponseEntity<List<CaptionInfo>> list(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        var items = captions.list(AuthenticatedUser.id(token), id).stream()
                .map(c -> new CaptionInfo(c.language(), c.source(), c.status())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(items);
    }

    @GetMapping("/v1/videos/{id}/captions/{language}")
    ResponseEntity<byte[]> get(@AuthenticationPrincipal Jwt token, @PathVariable UUID id, @PathVariable String language)
            throws IOException {
        byte[] vtt = captions.vtt(AuthenticatedUser.id(token), id, language);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/vtt;charset=UTF-8"))
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noCache()).body(vtt);
    }

    /** Finds moments in published videos by what is said. Accent-insensitive: "omo" finds "ọmọ". */
    @GetMapping("/v1/search/transcripts")
    ResponseEntity<SearchResponse> search(@AuthenticationPrincipal Jwt token, @RequestParam("q") String q,
            @RequestParam(required = false) String language, @RequestParam(required = false) Integer limit) {
        captions.beginSearch(AuthenticatedUser.id(token));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SearchResponse(captions.search(q, language, limit).matches()));
    }
}
