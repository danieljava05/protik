package com.protik;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application clock with one a test can set, so billing periods and report time limits can be
 * tested at exact moments. Import it only in tests that need it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    /** A settable clock (UTC). */
    public static class SettableClock extends Clock {

        private volatile Instant now = Instant.parse("2026-10-20T12:00:00Z");

        public void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Bean
    @Primary
    SettableClock settableClock() {
        return new SettableClock();
    }
}
