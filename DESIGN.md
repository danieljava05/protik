# ProTik Design

Living document. Updated with every ticket.

## Current architecture

Modular monolith (Spring Boot) with modules `identity`, `video`,
`feed`, `social`, `engagement`, `moderation`, `shared`. The modules
exist as empty packages; `ModularityTests` fails the build on boundary
violations or cycles (proven by a deliberate violation). Decisions so far:

| Decision | ADR |
|---|---|
| Apache-2.0 license | [0001](docs/adr/0001-license-apache-2.md) |
| Spring Modulith verifies boundaries; events between modules | [0002](docs/adr/0002-spring-modulith-for-module-boundaries.md) |
| One PostgreSQL-backed job queue (`SKIP LOCKED`) | [0003](docs/adr/0003-postgres-backed-job-queue.md) |
| ASR measurement protocol (proposed) | [0004](docs/adr/0004-asr-evaluation-datasets.md) |
| Maven build, Spring Boot 4.1.1, Modulith 2.1.1 | [0006](docs/adr/0006-maven-build.md) |
| One Flyway run per module, own schema and history | [0007](docs/adr/0007-per-module-migrations.md) |
| Persistence with `JdbcClient` and explicit SQL, not JPA | [0010](docs/adr/0010-persistence-with-jdbcclient.md) |
| Argon2id passwords, HS256 15-minute JWT access tokens, stateless, deny by default | [0011](docs/adr/0011-authentication.md) |
| Feed: bounded pool ranked per request, cursor pinned to a moment and the settings, events via a persistent registry | [0015](docs/adr/0015-feed-pipeline.md) |
| Follows/likes: existence via small public APIs, facts as events, counters recounted from a dirty set | [0016](docs/adr/0016-social-and-engagement.md) |
| Data budget: server advises, client reports, billing-day periods, estimates from the bitrate ladder | [0018](docs/adr/0018-data-budget.md) |
| Comments: plain text, one reply level, keyset paging, idempotent post, real delete, per-user rate limit | [0017](docs/adr/0017-comments.md) |
| `Ranker`: weighted sum of [0,1] components; the explanation is the score's own terms | [0014](docs/adr/0014-transparent-ranker.md) |
| Rate limiting in PostgreSQL: reserve-then-refund, three login keys, hashed keys | [0013](docs/adr/0013-rate-limiting.md) |
| Refresh tokens: random, hashed, rotated, reuse revokes the session, 10 s grace | [0012](docs/adr/0012-refresh-tokens-and-sessions.md) |
| Idempotency-Key: claim + work + response in one transaction, HMAC fingerprint | [0009](docs/adr/0009-idempotency-keys.md) |
| Job queue: at-least-once, leases, fencing by attempt, backoff | [0008](docs/adr/0008-job-queue-semantics.md) |
| Schema per module, UUIDv7 IDs, no cross-module FKs, event-fed counters and read models | [0005](docs/adr/0005-database-structure.md) |

## Schema

Logical model: [docs/schema.md](docs/schema.md). Implemented as
per-module Flyway migrations (`src/main/resources/db/migration/<module>/`,
[ADR 0007](docs/adr/0007-per-module-migrations.md)) and tested against
PostgreSQL 17 by `SchemaTests` (the tests prove constraints and
structure, not performance). Each module owns one PostgreSQL schema; a
catalog test forbids cross-schema foreign keys.

## Job queue

The one shared queue (`shared.job`): modules call `JobQueue.enqueue` inside
their own transaction (outside one it throws) and register a `JobHandler`
bean per job type. Workers claim with `FOR UPDATE SKIP LOCKED`, own the job
through a lease, and every completion, failure or lease extension is fenced
by the attempt number, so a stalled worker cannot overwrite its successor.
Failures retry with exponential backoff and jitter, then park as `DEAD`.
Delivery is at-least-once: handlers must be idempotent. Proven with
real threads against PostgreSQL in `JobStoreConcurrencyTests` and
`JobWorkerTests` (no double claim among 16 threads and 600 jobs; skip over
locked rows; stale-worker fencing; concurrent recovery recovers each job
exactly once; long jobs keep their lease). Details and rejected options in
[ADR 0008](docs/adr/0008-job-queue-semantics.md).

## Authentication (T1.1)

`POST /v1/auth/register` creates account, email login, Argon2id credential and profile in one
transaction (idempotency-key capable); `POST /v1/auth/login` takes a username or email and returns
a 15-minute HS256 bearer token; `GET /v1/me` proves it. Everything not on an explicit allow-list
(register, login, health, info, OpenAPI document) needs a valid token: deny by default, stateless,
no cookies, so CSRF protection is deliberately off. 401/403 are problem documents. Tests cover
forged, expired, tampered, `alg: none` and foreign-issuer tokens, indistinguishable login failures
(including a dummy hash verification for unknown accounts), atomic registration under 10 concurrent
requests, and that passwords never reach logs. Details in
[ADR 0011](docs/adr/0011-authentication.md).

## Sessions (T1.2)

Login starts a session (a "family") and returns a refresh token: 256 random bits, stored only as a
SHA-256, valid 30 days, session capped at 90. `POST /v1/auth/refresh` rotates it, so a session has
exactly one live token. Presenting an already-rotated token revokes the whole session (token theft
protection), except within a 10 s grace window where it is just refused (duplicate or racing
requests). Concurrent refreshes are serialised by a row lock on the session, with the rotation update
itself as a second guard. `logout` and `logout-all` end sessions; all failures return one identical
401. Proven by 20 tests, including 8 simultaneous refreshes (one winner, session intact) and
mutation checks. Details in [ADR 0012](docs/adr/0012-refresh-tokens-and-sessions.md).

## Transparent ranking (T2.1, T2.2)

`Ranker` orders candidates; `WeightedRanker` sums `weight x value` over pluggable `ScoreFunction`s
(RECENCY, ENGAGEMENT, FOLLOWING, TOPIC) and returns, per item, the exact list of terms it summed, so the
explanation cannot disagree with the score (checked on 2,000 random inputs). Ties break by newest, then
by unsigned UUID order, which is also PostgreSQL's order. Modes are data (published weights, plus a
`followed_only` filter); a viewer picks a mode and overrides weights through `GET /v1/feed/modes`,
`GET/PUT /v1/feed/preferences` (saves are atomic and idempotent; weights are validated to 0..1 with at
most 3 decimals rather than rounded silently). A test fails if a component exists in the database
without a function behind it, or the reverse.

**Measured** (unit test, single thread, warmed up, AMD A8-7410, 4 cores, 14.9 GB RAM, Java 25):
ranking 500 candidates takes about 6 ms, 10,000 about 100 ms, 100,000 about 750 ms. Replacing string
UUID comparison with unsigned long comparison cut the 500-candidate case from 53 ms to 6 ms. These are
timings of the ranking step alone, not of a feed request.

## The feed (T2.3)

`GET /v1/feed?limit&cursor&explain` returns the viewer's feed: the newest 500 candidates of the last 14 days
(taken from the feed's own read models, restricted to followed creators in that mode) are ranked in memory with
the viewer's mode and weights, then sliced after their cursor. Each item carries its explanation unless
`explain=false`, which roughly halves the response. The cursor pins the session's start time and a fingerprint of
the settings, so pages neither shuffle when new videos arrive nor mix two rule sets (changing settings
mid-session gives 409). Read models are kept current from the video module's events (`VideoPublished`,
`VideoRemoved`) through Modulith's persistent registry (table in the `shared` schema, handled events deleted,
unfinished ones re-delivered at startup); handlers are idempotent, a rolled-back publish is never delivered, and a
poison event stays in the registry without blocking others. Details in [ADR 0015](docs/adr/0015-feed-pipeline.md).

**Measured** (`FeedPerformanceTests`: 20,000 candidates over 2,000 creators, 150 followed creators, local
PostgreSQL 17, in-process MockMvc, 200 requests after warm-up, first page of 20, AMD A8-7410 4 cores, 14.9 GB,
Java 25): **p50 37 to 40 ms, p95 48 to 68 ms, p99 65 to 110 ms** over three runs (the third includes the data-budget advice) (the spread is run-to-run noise on a
slow laptop). This excludes the network, TLS and a real HTTP server, so it is a lower bound on what a client would see,
not a latency claim.

## Follows, likes and counters (T5.1)

`PUT/DELETE /v1/users/{id}/follow` and `PUT/DELETE /v1/videos/{id}/like` are idempotent (204 whether or not
anything changed) and announce a change only when one really happened. Targets are checked through
`AccountDirectory` (identity) and `VideoCatalog` (video), never by reading their tables. Counters are not
incremented per like (a hot row, and unsafe under at-least-once delivery): each like marks its video in a tiny
`dirty_counters` set, a refresher about once per second recounts those videos from the like rows, stores the
counter and announces it as `VideoStatsChanged` (stamped with the time computed, so the feed ignores a stale
one that arrives late). A crash re-queues the work because the dirty mark is removed in the same transaction as
the update, and an hourly reconciliation recomputes everything from the truth and announces what it corrected.
The feed keeps its own copies of follows, likes and counters from these events, and derives a viewer's interests
from the tags of the videos they liked. Proven by tests including 300 users x 8 operations across 16 threads
(every counter equals the like rows afterwards) and an end-to-end chain: follow and like through the API, feed
reflects it. A test also caught a real bug: a scheduled method calling a transactional method on its own object
skipped the transaction proxy, so events were stored but never delivered; the scheduling now lives in a separate
bean. Details in [ADR 0016](docs/adr/0016-social-and-engagement.md).

## Comments (T5.1)

`POST /v1/videos/{id}/comments` (with `Idempotency-Key`, and at most 20 per user per minute), `GET /v1/videos/{id}/comments`
(newest first), `GET /v1/comments/{id}/replies` (oldest first), `DELETE /v1/comments/{id}` (author only; replies go with it).
Text is 1 to 2,200 characters (counted as people see them, so one emoji is one), plain, with control characters refused; it is
stored exactly as given and never treated as markup, so clients must escape it. One level of replies; paging is by an opaque
`(time, id)` cursor and stays stable when many comments share a timestamp. Adding or deleting announces
`CommentCountChanged`; the engagement refresher recounts through social's `CommentCounts` and the total (replies included) reaches the
feed. An hourly reconciliation also repairs comment counters. Rejected attempts count toward the posting limit. Details in
[ADR 0017](docs/adr/0017-comments.md).

## Data budget (T3.1)

`GET/PUT /v1/data/settings` (data saver, monthly cap in MB or none, prefetch cap, billing day 1 to 28),
`POST /v1/data/usage` (up to 100 events, each with a client-chosen ID so a resent batch counts nothing twice),
`GET /v1/data/usage` (this billing period's usage by rendition and the advice). The budget period follows the
day the user's plan renews, not the calendar month. One pure function turns settings and usage into advice:
`UNLIMITED`, `OK`, `LOW` (90% used: use 144p), `EXHAUSTED` (use audio, no preload); data saver forces 144p and no
preload. Every feed page carries that advice (`dataPolicy`) and each item the estimated bytes at every rendition
(`estimatedBytes`, from duration and the bitrate ladder; `estimates=false` leaves them out). **The server never
blocks playback**: it cannot see bytes object storage serves, so usage is reported by the app and the budget is
advice. Usage older than 400 days is deleted. Details in [ADR 0018](docs/adr/0018-data-budget.md).

## Moderation and account deletion (T5.2)
Reports (video, comment, account) feed a queue ordered child safety first, then oldest first. A moderator's action
(takedown, hide comment, suspend account, dismiss) changes the target through the owning module's small public API and
writes the audit row in the same transaction; the audit table is append-only by trigger. Roles are rows in
`identity.account_roles` carried in the access token's `roles` claim. Account deletion is a coordinated protocol: identity
marks the account pending and announces it, each module deletes its own data and acknowledges, and identity deletes the
account when all five have. See [ADR 0019](docs/adr/0019-moderation.md) and [ADR 0020](docs/adr/0020-account-deletion.md).

## Uploads and transcoding (T1.3, T1.4)
`POST /v1/videos` creates a video; `PUT /v1/videos/{id}/content` streams the file (size-capped while writing, signature
checked against the declared type) into a quarantine key and queues `video.process`. That job scans (ClamAV `INSTREAM`),
probes (`ffprobe`), encodes each rendition no larger than the source plus audio only (`ffmpeg`, H.264/AAC, `+faststart`)
and publishes in one transaction, after which the original is deleted. Files are served from
`/v1/videos/{id}/renditions/{code}` with Range support. Deleted assets leave tombstones that a sweeper turns into deleted
files, including when an account is deleted. See [ADR 0022](docs/adr/0022-uploads-and-transcoding.md).

## Captions and transcript search (T4.1-T4.3)
Creators upload WebVTT for their own videos; cues are stored as plain-text segments with a rendered `.vtt` file, and
search runs over them with `unaccent` + the `simple` text-search configuration, so "omo" finds "ọmọ". Machine captions go
through a `Transcriber` port and a job that does nothing unless the language's `asr_supported` flag is set, which requires a
measured word error rate. An `Evaluation` tool computes WER and CER under both diacritic policies with bootstrap intervals.
See [ADR 0023](docs/adr/0023-captions-and-transcript-search.md).

## Rate limiting (T1.5)

Login failures are counted per account+address (5 / 15 min), per account (20) and per address (100,
generous because mobile carriers share addresses); registrations per address (20 / hour). Attempts
are counted atomically *before* the password is checked and handed back on success, so a blocked
request never costs an Argon2 verification: with 30 simultaneous guesses exactly 5 reach the hash.
Unknown accounts are throttled identically, a correct password does not bypass a block, keys are
SHA-256 hashes (no emails or IPs stored), and `X-Forwarded-For` is ignored. Blocked requests get 429
with `Retry-After`. Details in [ADR 0013](docs/adr/0013-rate-limiting.md).

## Idempotency

`IdempotencyService.execute` claims the `Idempotency-Key`, runs the action and stores
its response in one transaction, so a crash cannot leave work done without its key. Concurrent
duplicates wait on the unique index and then replay (12 simultaneous requests ran the action
once). A different request under the same key gets 422; failed actions store nothing; keys expire
after 24h. The stored request fingerprint is an HMAC, because bodies may contain passwords.
Login and refresh are a documented exception (responses hold tokens). Details in
[ADR 0009](docs/adr/0009-idempotency-keys.md).

Identifiers are generated by `shared.id.Ids` (UUIDv7, ADR 0005).

## Cross-cutting behaviour

- **Correlation IDs:** `CorrelationIdFilter` accepts a well-formed
  `X-Correlation-Id` (1-64 chars of `A-Za-z0-9._-`), otherwise generates
  a UUID; it is in every log line, the response header and error bodies.
- **Errors:** every error is `application/problem+json` (RFC 9457) from
  `GlobalExceptionHandler`; unexpected exceptions return a generic body
  and are logged with full detail under the same correlation ID.
- **OpenAPI:** generated by springdoc at `/v3/api-docs`, limited to
  `/v1/**`, no bundled UI.

## Deliberate denormalizations

Planned (D1 to D5: caption search column, feed candidate read model,
candidate stats, cached counters, rendered VTT). Each is named with its
consistency mechanism in
[docs/schema.md](docs/schema.md#deliberate-denormalizations).

## What this does not handle

Almost everything: only the skeleton and cross-cutting plumbing exist
(schema, migrations, error format, request IDs, OpenAPI). There are no
features and no authentication; every endpoint is open. Known gaps:

- Job queue: no throughput benchmark; concurrency was tested with threads
  in one process, not several processes. No priorities, per-type
  concurrency limits, deduplication, or queue-depth metrics. A handler
  that loses its lease keeps running to the end (only its result is
  discarded). Workers poll once per second when idle.
- Sessions: a client whose refresh response was lost holds a rotated token and must log in again
  (grace period rejects instead of re-issuing). Access tokens stay valid up to 15 minutes after
  logout or revocation. No device list, no device binding. Account suspension takes effect at the
  next refresh.
- Authentication: no MFA, no changing a password or email while signed in, no notification that a password
  changed, and no token revocation before expiry. HS256 with a shared secret; a lost secret lets anyone mint tokens.
  Registration reveals whether a username or email exists. Email verification and password reset work end to end in tests,
  but **no email provider exists, so in any deployment nobody receives the mail** (`LoggingEmailSender` delivers nothing);
  verification gates nothing yet. The common-password check is a bundled list of 9,126 passwords, not a breach database.
  Reset hides whether an address has an account in what it answers, though a timing difference is possible.
  **Choose a mail provider, sending domain (SPF, DKIM) and legal text before any public deployment.**
- Social: no cap on follows, no blocks or mutes, no follower/following lists (only the relationship is queryable),
  no notifications, no editing of comments, no comment reactions or mentions, no spam or profanity detection, moderators can hide a comment but there is no restore.
  Counters lag a like or comment by about a second. A like that reaches the feed before the feed has indexed the video does not influence interests.
  The demo counters are invented and the hourly reconciliation zeroes them.
- Uploads: **the file store is a directory on one machine; there is no S3/MinIO adapter**, no resumable upload (a dropped
  connection restarts the file), no HLS or mid-playback quality switching, no per-person storage quota, and processing
  happens on the API's machine. FFmpeg runs as a plain child process with limits and a timeout, **which is not a sandbox**:
  a flaw in FFmpeg would run with the application's rights, so production must run the worker in a container or user with no
  network, a read-only file system apart from its work directory, and CPU and memory limits (not done). The ClamAV client
  was checked against a fake daemon locally and against a real daemon only in CI; clamd's `StreamMaxLength` must be at least
  `protik.upload.max-bytes` or every large upload fails closed. `protik.scan.mode=disabled` skips scanning (the video is
  marked `SKIPPED`, never clean) and must never be used in production. A video whose scanner stays down is never published,
  and after the job's attempts run out it stays `PROCESSING` with a dead job for an operator. Encoding quality settings
  (CRF 28, `veryfast`) are untuned. The ladder is tested on generated test pictures only, which are not representative
  of real footage, so no storage or bandwidth claim is made from them.
- Captions: **no speech recognition model exists, no ASR test set has been chosen, and no word error rate has been
  measured for any language**, so no machine caption is ever produced and the project makes no accuracy claim. Search
  matches whole words only (no prefix or fuzzy matching), returns at most 50 results and has no paging, ranking is the
  database's default untuned, and Nigerian Pidgin spelling variation is not handled. Caption and search text is plain text
  that clients must escape.
- Moderation: no API grants roles (a database operator inserts the first moderator), and a role change reaches the
  person's access token only at their next login or refresh (up to 15 minutes). No restore, no appeals, no automated
  detection, no legal procedure for child-safety reports (**a legal question to settle before launch**), no notification
  to the reporter or the reported person. Two moderators acting on the same report are serialized by a row lock, the second
  gets 409.
- Account deletion: asynchronous; an access token issued before the request works for up to 15 minutes, so something
  written in that window after a module finished may survive. Stored files, captions and assets are not covered because
  they do not exist yet. The completeness test finds forgotten tables by column name only. No data export, no cooling-off
  period, backups are out of reach. Comments others wrote on a deleted person's video are not deleted with it.
- Data budget: usage is reported by the app and unverified, so a dishonest app only misleads its own user; nothing is
  enforced server-side; size estimates are upper bounds from the bitrate ladder, not measurements (until T1.4); no
  Wi-Fi versus mobile distinction; `feed.usage_events` has only its default partition (no monthly partition
  maintenance yet, the 400-day purge keeps it bounded). The advice adds two small queries per feed page.
- Feed: **no real videos exist yet** (uploads are T1.3/T1.4), so it has only run on test data and the opt-in
  demo set. Nothing populates the follow graph, interests or engagement counters until the social and
  engagement modules exist, so FOLLOWING and TOPIC are 0 for everyone in practice. The ranker only sees the
  newest 500 candidates; counters are live, so an item can be repeated or skipped at a page boundary if its
  counters change between pages; already-seen videos are not hidden; blocked creators and language
  preferences are ignored; there is no rebuild/backfill command for the read models.
- Ranking quality: the formulas and constants are starting points, not tuned or evaluated: no claim of feed
  quality is made. No diversity, negative signals or cold-start handling.
- Rate limiting: fixed windows (up to twice the limit across a boundary); an attacker using many
  addresses can lock one account for a window; behind a proxy the deployment must configure trusted
  forwarded headers or every user shares the proxy's address; refresh and other endpoints are not
  limited; no CAPTCHA, no alerting.
- Idempotency: anonymous endpoints share one key scope (clients must use random keys); a long
  action holds a lock on its key; stored response bodies must never contain secrets; each new POST
  must call the service explicitly (not automatic).
- Two application instances starting at once (concurrent migrations) are
  not tested; we rely on Flyway's locking.
- Migrations were verified only against the PostgreSQL 17 test
  container's superuser, not a restricted database owner.
- The OpenAPI test covers generation and path scoping; request/response
  accuracy is only checkable once real endpoints exist.
- Error `type` URIs are `urn:protik:problem:*` identifiers, not
  dereferenceable documentation pages.
- Database-level module isolation is by schema separation plus a test,
  not by per-module database roles (ADR 0005, upgrade path).
- No cross-module referential integrity; orphans are handled by events
  and reconciliation.
- Pidgin ASR has no confirmed standard benchmark (ADR 0004).
- Legal review, privacy notice and terms are not done; the service must
  not be publicly deployed before them.

## Rejected alternatives

Recorded in the relevant ADR.
