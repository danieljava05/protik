package com.protik.shared.error;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error kinds. The {@code type} URI is an identifier
 * (RFC 9457 allows non-dereferenceable URIs); clients switch on it, never on
 * the human-readable title or detail.
 */
public enum ProblemType {
    VALIDATION_FAILED("validation-failed", HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS("invalid-credentials", HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    INVALID_REFRESH_TOKEN("invalid-refresh-token", HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN, "Forbidden"),
    NOT_FOUND("not-found", HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT("conflict", HttpStatus.CONFLICT, "Conflict"),
    PAYLOAD_TOO_LARGE("payload-too-large", HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),
    UNSUPPORTED_MEDIA("unsupported-media-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    TOO_MANY_REQUESTS("too-many-attempts", HttpStatus.TOO_MANY_REQUESTS, "Too many attempts"),
    IDEMPOTENCY_KEY_REUSED("idempotency-key-reused", HttpStatus.UNPROCESSABLE_CONTENT,
            "Idempotency key reused for a different request"),
    INTERNAL_ERROR("internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final URI type;
    private final HttpStatus status;
    private final String title;

    ProblemType(String slug, HttpStatus status, String title) {
        this.type = URI.create("urn:protik:problem:" + slug);
        this.status = status;
        this.title = title;
    }

    public URI type() {
        return type;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
