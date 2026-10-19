package com.protik.shared.idempotency;

import org.springframework.http.ResponseEntity;

/**
 * The result of an idempotent action: what to send to the client, and whether
 * it was replayed from an earlier identical request.
 */
public record IdempotentResponse<T>(int status, T body, boolean replayed) {

    public ResponseEntity<T> toResponseEntity() {
        var builder = ResponseEntity.status(status);
        if (replayed) {
            builder.header(IdempotencyService.REPLAY_HEADER, "true");
        }
        return builder.body(body);
    }
}
