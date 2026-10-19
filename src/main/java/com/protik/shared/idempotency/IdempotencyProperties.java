package com.protik.shared.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.idempotency.*} (ADR 0009). */
@ConfigurationProperties("protik.idempotency")
public record IdempotencyProperties(
        /* How long a key remembers its response. */
        @DefaultValue("24h") Duration ttl,
        /* How often expired keys are deleted. */
        @DefaultValue("1h") Duration purgeInterval,
        /*
         * Secret for the request fingerprint (HMAC-SHA-256). Request bodies may contain
         * passwords, so the fingerprint must not be an unkeyed hash. The default is for local
         * development only; set IDEMPOTENCY_HASH_KEY in any real deployment.
         */
        @DefaultValue("local-development-key-not-a-secret") String hashKey) {

    static final String DEVELOPMENT_KEY = "local-development-key-not-a-secret";

    public IdempotencyProperties {
        if (hashKey == null || hashKey.length() < 16) {
            throw new IllegalArgumentException("protik.idempotency.hash-key must be at least 16 characters");
        }
    }
}
