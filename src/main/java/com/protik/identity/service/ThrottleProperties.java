package com.protik.identity.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Limits for login and registration under {@code protik.throttle.*} (ADR 0013). */
@ConfigurationProperties("protik.throttle")
public record ThrottleProperties(
        /* Failed logins per account from one client address per window. */
        @DefaultValue("5") int loginPairLimit,
        /* Failed logins per account from all addresses together. */
        @DefaultValue("20") int loginAccountLimit,
        /* Failed logins from one address across all accounts (generous: carrier NAT shares addresses). */
        @DefaultValue("100") int loginIpLimit,
        @DefaultValue("15m") Duration loginWindow,
        /* Registration attempts per client address. */
        @DefaultValue("20") int registerIpLimit,
        @DefaultValue("1h") Duration registerWindow) {

    public ThrottleProperties {
        if (loginPairLimit < 1 || loginAccountLimit < 1 || loginIpLimit < 1 || registerIpLimit < 1) {
            throw new IllegalArgumentException("protik.throttle limits must be at least 1");
        }
    }
}
