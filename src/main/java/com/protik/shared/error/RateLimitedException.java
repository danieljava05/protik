package com.protik.shared.error;

import java.time.Duration;

/** A client exceeded a rate limit; rendered as 429 with a {@code Retry-After} header. */
public class RateLimitedException extends ApiException {

    private final Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(ProblemType.TOO_MANY_REQUESTS, "Too many attempts. Try again later.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
