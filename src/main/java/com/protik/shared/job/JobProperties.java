package com.protik.shared.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.jobs.*}. Defaults are explained in ADR 0008. */
@ConfigurationProperties("protik.jobs")
public record JobProperties(
        /* Run workers in this process. */
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2") int workerThreads,
        /* How long an idle worker waits before asking for work again. */
        @DefaultValue("1s") Duration pollInterval,
        /* How long a claimed job is owned before it may be taken from a silent worker. */
        @DefaultValue("5m") Duration lease,
        /* First retry delay; doubles each attempt up to retryMax. */
        @DefaultValue("5s") Duration retryBase,
        @DefaultValue("1h") Duration retryMax,
        /* How often expired leases are recovered and old DONE jobs purged. */
        @DefaultValue("30s") Duration maintenanceInterval,
        /* How long DONE jobs are kept. DEAD jobs are never purged automatically. */
        @DefaultValue("7d") Duration retention,
        /* How long shutdown waits for running jobs before interrupting them. */
        @DefaultValue("30s") Duration shutdownTimeout) {

    public JobProperties {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("protik.jobs.worker-threads must be at least 1");
        }
    }
}
