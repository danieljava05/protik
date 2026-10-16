# ADR 0003: One shared job queue, backed by PostgreSQL

- Status: accepted
- Date: 2026-10-17

## Context

Transcoding (several renditions including 144p and audio-only) and ASR
captioning are long-running. VISION.md requires one shared queue, not
one per feature. Jobs must not be lost, must be retryable, and a job
that enqueues must do so atomically with the business change that
caused it (for example "video row marked uploaded" and "transcode job
created").

## Options considered

### A. PostgreSQL table with `SELECT ... FOR UPDATE SKIP LOCKED`
Transactional with business data (same commit), no new infrastructure,
easy to inspect and test with Testcontainers. Throughput is bounded by
the database, which is ample for transcode/ASR jobs (heavy, few per
second).

### B. Redis-based queue
Faster for high-rate tiny jobs, but enqueue is not atomic with the
Postgres commit (dual-write problem), and durability depends on Redis
persistence settings.

### C. Dedicated broker (RabbitMQ/Kafka/SQS)
Strong semantics at scale, but another system to operate and no
measured need.

## Decision

Option A. A single `job` table in the `shared` module with: type,
payload, status, attempts, `run_at`, lease expiry and last error.
Workers claim with `SKIP LOCKED` and a lease; expired leases are
re-claimed. Handlers are idempotent. Failed jobs retry with backoff,
then move to a dead state.

## Consequences

- Enqueue and business change commit together.
- Throughput ceiling is the database; revisit with a measurement, not a
  guess. Redis remains for caching and counters, not for jobs.
- Workers run FFmpeg out of process and isolated (see CONTRIBUTING).
- Concurrency (no double-claim, lease recovery) must be proven by a
  Testcontainers test.

## Why the alternatives were rejected

B introduces a dual-write inconsistency to solve a throughput problem
we do not have. C adds operational burden with no measured need.
