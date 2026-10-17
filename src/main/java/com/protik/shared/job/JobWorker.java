package com.protik.shared.job;

import com.protik.shared.web.CorrelationIdFilter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;

/**
 * Claims and runs jobs on a fixed set of threads. The claim transaction is
 * short and commits immediately; the handler then runs with no database
 * transaction open, owned by a lease (ADR 0008). Thread 0 additionally does
 * the periodic maintenance: recovering expired leases and purging old jobs.
 */
class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JobStore store;
    private final Map<String, JobHandler> handlers;
    private final JobProperties props;
    private final Object idle = new Object();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    JobWorker(JobStore store, List<JobHandler> handlerBeans, JobProperties props) {
        this.store = store;
        this.props = props;
        var byType = new java.util.HashMap<String, JobHandler>();
        for (JobHandler handler : handlerBeans) {
            JobHandler previous = byType.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("Two JobHandlers serve job type '" + handler.type() + "': "
                        + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(byType);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        for (int i = 0; i < props.workerThreads(); i++) {
            Thread t = new Thread(this::loop, "job-worker-" + i);
            t.setUncaughtExceptionHandler((th, e) -> log.error("Job worker {} died", th.getName(), e));
            threads.add(t);
            t.start();
        }
        log.info("Job workers started: {} thread(s), handlers for {}", props.workerThreads(), handlers.keySet());
    }

    @Override
    public synchronized void stop() {
        running = false;
        synchronized (idle) {
            idle.notifyAll();
        }
        long deadline = System.nanoTime() + props.shutdownTimeout().toNanos();
        for (Thread t : threads) {
            try {
                t.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
                if (t.isAlive()) {
                    // Out of patience: interrupt. The job's lease will expire and it will be recovered.
                    log.warn("Interrupting {} still running a job after shutdown timeout", t.getName());
                    t.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        boolean doesMaintenance = Thread.currentThread().getName().endsWith("-0");
        long nextMaintenance = System.nanoTime();
        while (running) {
            try {
                if (doesMaintenance && System.nanoTime() - nextMaintenance >= 0) {
                    maintenance();
                    nextMaintenance = System.nanoTime() + props.maintenanceInterval().toNanos();
                }
                List<JobStore.ClaimedJob> claimed = store.claim(handlers.keySet(), 1, props.lease());
                if (claimed.isEmpty()) {
                    sleepIdle();
                } else {
                    run(claimed.getFirst());
                }
            } catch (Exception e) {
                // Most likely the database is unreachable. Back off and keep the thread alive.
                log.error("Job worker loop error", e);
                sleepIdle();
            }
        }
    }

    void maintenance() {
        int recovered = store.requeueExpired();
        if (recovered > 0) {
            log.warn("Recovered {} job(s) whose lease expired", recovered);
        }
        int purged = store.purgeDone(props.retention());
        if (purged > 0) {
            log.info("Purged {} finished job(s)", purged);
        }
    }

    private void run(JobStore.ClaimedJob job) {
        JobHandler handler = handlers.get(job.type());
        // The correlation ID makes every log line of a job traceable, like a request.
        MDC.put(CorrelationIdFilter.MDC_KEY, "job-" + job.id());
        try {
            handler.handle(new Context(job));
            if (!store.complete(job.id(), job.attempt())) {
                log.warn("Job {} ({}) finished but its lease was lost; result discarded", job.id(), job.type());
            }
        } catch (Throwable t) {
            log.error("Job {} ({}) attempt {}/{} failed", job.id(), job.type(), job.attempt(), job.maxAttempts(), t);
            try {
                boolean owned = store.fail(job.id(), job.attempt(), describe(t), backoff(job.attempt(), props));
                if (!owned) {
                    log.warn("Job {} ({}) failed but its lease was lost; failure ignored", job.id(), job.type());
                }
            } catch (RuntimeException dbError) {
                // Could not record the failure; the lease will expire and the job be recovered.
                log.error("Could not record failure of job {}", job.id(), dbError);
            }
            if (t instanceof InterruptedException && !running) {
                // Shutdown interrupted us: keep the flag so the loop ends. While running, a handler
                // that was interrupted must not leave the flag set, or the thread would spin.
                Thread.currentThread().interrupt();
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void sleepIdle() {
        synchronized (idle) {
            if (running) {
                try {
                    idle.wait(Math.max(1, props.pollInterval().toMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Exponential backoff {@code base * 2^(attempt-1)} capped at {@code retryMax},
     * with +/-20% jitter so a burst of failures does not retry in lock step.
     */
    static Duration backoff(int attempt, JobProperties props) {
        double exponential = props.retryBase().toMillis() * Math.pow(2, Math.max(0, attempt - 1));
        double capped = Math.min(exponential, props.retryMax().toMillis());
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) Math.min(capped * jitter, props.retryMax().toMillis()));
    }

    private static String describe(Throwable t) {
        String text = t.getClass().getName() + ": " + t.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    private final class Context implements JobContext {

        private final JobStore.ClaimedJob job;

        Context(JobStore.ClaimedJob job) {
            this.job = job;
        }

        @Override
        public UUID id() {
            return job.id();
        }

        @Override
        public String type() {
            return job.type();
        }

        @Override
        public String payloadJson() {
            return job.payloadJson();
        }

        @Override
        public int attempt() {
            return job.attempt();
        }

        @Override
        public boolean extendLease(Duration duration) {
            return store.extendLease(job.id(), job.attempt(), duration);
        }
    }
}
