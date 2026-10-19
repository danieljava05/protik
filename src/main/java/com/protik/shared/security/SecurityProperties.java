package com.protik.shared.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.security.*} (ADR 0011). */
@ConfigurationProperties("protik.security")
public record SecurityProperties(
        /*
         * HS256 signing secret, at least 32 bytes (env JWT_SECRET). If blank, a random one is
         * generated at startup: tokens then do not survive a restart, and an instance started
         * without the shared secret fails loudly instead of using a guessable default.
         */
        @DefaultValue("") String jwtSecret,
        @DefaultValue("protik") String issuer,
        /* Access tokens are short-lived; sessions are renewed with refresh tokens (T1.2). */
        @DefaultValue("15m") Duration accessTokenTtl,
        /* Browser origins allowed to call the API (CORS). */
        @DefaultValue("http://localhost:3000") List<String> allowedOrigins) {

    public SecurityProperties {
        if (accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalArgumentException("protik.security.access-token-ttl must be positive");
        }
        if (!jwtSecret.isBlank() && jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("protik.security.jwt-secret must be at least 32 bytes");
        }
    }
}
