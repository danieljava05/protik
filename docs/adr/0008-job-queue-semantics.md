# ADR 0008: Job queue semantics

- Status: accepted
- Date: 2026-10-17

ADR 0003 chose PostgreSQL with `SELECT ... FOR UPDATE SKIP LOCKED` as the
single shared job queue. This ADR fixes the behaviour that sits on top:
delivery guarantees, how a crashed worker is detected, how stale workers
are stopped from corrupting state, and how failures are retried.

## Context

Jobs are long (FFmpeg transcodes, ASR) and must survive crashes. The job
must be created in the same transaction as the business change that
needs it. Several workers, possibly several application instances, pull
from the same table. A worker can die at any time, or stall long enough
that its job is handed to someone else.

## Decision 1: delivery guarantee

**At-least-once.** A job may run more than once (a stalled worker may
come back after its job was re-assigned). Handlers must be idempotent;
the schema helps, for example `UNIQUE NULLS NOT DISTINCT` on
`video_assets` makes a repeated transcode unable to create duplicates.
Exactly-once is not promised and cannot be, once side effects leave the
database (object storage).

*Rejected: at-most-once* (would silently lose work on a crash).

## Decision 2: leases and fencing

A claimed job gets a lease (`lease_expires_at`, default 5 minutes). If
the lease passes, the job is requeued. To stop the original, slow worker
from overwriting the new worker's result, every completion, failure and
lease extension must match the **attempt number** it was claimed with:
`WHERE id = ? AND status = 'RUNNING' AND attempts = ?`. A stale worker
updates zero rows, learns it lost the job, and discards its result.

*Rejected: a separate lease-token column.* The attempt counter is
already incremented at every claim, is unique per claim, and needs no new
column. *Rejected: no fencing,* which allows a late worker to mark a
re-run job done or overwrite its error.

All time comparisons use the **database clock** (`now()`), never the
application's, so workers on machines with skewed clocks agree.

## Decision 3: long jobs

Handlers receive a `JobContext` and may call `extendLease(duration)`
(fenced like everything else). Transcodes longer than the default lease
extend it periodically; a dead worker then stops extending and the job is
recovered after at most one lease period.

*Rejected: very long fixed leases,* which delay crash recovery by hours.

## Decision 4: retries

On failure the job returns to `QUEUED` with exponential backoff
(`base * 2^(attempt-1)`, capped, with +/-20% jitter so a burst of failures
does not retry in lock step). After `max_attempts` (default 5) it becomes
`DEAD` and stays for an operator; nothing deletes dead jobs
automatically. Finished (`DONE`) jobs are purged after a retention period
so the table and its indexes stay small. Lease expiry counts as an
attempt, so a job that crashes its worker every time cannot loop forever.

## Decision 5: who claims what

A worker claims only job types it has a handler for. A job of an unknown
type stays queued rather than being failed, because another deployment
(or a later version) may handle it.

## Decision 6: polling, not LISTEN/NOTIFY

Workers poll (default every second when idle). `LISTEN/NOTIFY` would cut
latency to milliseconds but adds a long-lived connection per instance and
a fallback poll anyway. Jobs here take seconds to minutes, so one second
of pickup latency is irrelevant. Revisit only with a measurement.

## Decision 7: enqueue is transactional by construction

`JobQueue.enqueue` uses `Propagation.MANDATORY`: calling it outside a
transaction is an error. This turns "the job is created atomically with
the business change" from a convention into something the code enforces.

## Consequences

- Handlers must be idempotent, and tests of new handlers should run them
  twice.
- A worker that loses its lease keeps running its handler to the end; we
  cannot safely kill arbitrary work. Its result is discarded, but its
  external side effects (for example files written) still happen. Handlers
  should write to unique keys and tolerate duplicates.
- Maximum one lease period of delay to recover from a crash (default 5
  minutes).
- No deduplication of identical jobs: enqueueing the same logical job
  twice runs it twice. A dedup key can be added with the first feature
  that needs it.
- Throughput is bounded by PostgreSQL. No benchmark has been run; none is
  claimed.

## What is not covered
- Job priorities and per-type concurrency limits.
- Observability beyond logs (metrics for queue depth, age, dead jobs).
- Multiple application instances were tested as multiple threads sharing
  one database, not as separate processes.
