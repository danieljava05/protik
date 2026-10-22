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
Java 25): **p50 39 to 40 ms, p95 57 to 68 ms, p99 72 to 110 ms** over two runs (the spread is run-to-run noise on a
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
- Authentication: no email verification, password reset, breached-password check, MFA, or token
  revocation before expiry. HS256 with a shared secret; a lost secret lets anyone mint tokens.
  Registration reveals whether a username or email exists. **Do not deploy publicly before the
  account hardening in roadmap T1.6.**
- Social: no cap on follows, no blocks or mutes, no follower/following lists (only the relationship is queryable),
  no notifications, no editing of comments, no comment reactions or mentions, no spam or profanity detection, no moderator hide or
  remove yet (T5.2). Counters lag a like or comment by about a second. A like that reaches the feed before the feed has indexed the video does not influence interests.
  The demo counters are invented and the hourly reconciliation zeroes them.
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
