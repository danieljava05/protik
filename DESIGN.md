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
