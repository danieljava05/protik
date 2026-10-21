# ProTik

**Short-form video you control, built for expensive data and African languages.**

![Status: pre-alpha](https://img.shields.io/badge/status-pre--alpha-orange)
![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)
![Java 25](https://img.shields.io/badge/Java-25-informational)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F)

> **Status: pre-alpha.** The architecture is documented and the build
> skeleton exists; no product features are written yet. No
> performance, cost or accuracy claim is made until it is measured, with
> hardware and method stated. See the [roadmap](docs/ROADMAP.md).

## Why

Short-video apps are built for cheap data and English-first audiences.
For a Nigerian user on a prepaid mobile plan, that means three problems:

| Problem | ProTik's answer | Measured by |
|---|---|---|
| **Opaque ranking.** You don't know why you see a video. | Every feed item carries structured score components; pick a ranking mode and adjust weights. | Feed p99 latency; score components exposed in the API |
| **Data cost.** Scrolling burns the budget. | Monthly data budget, prefetch cap, 144p and audio-only renditions. | MB per 10 minutes of scrolling, data saver on vs off |
| **Weak African-language support.** Captions and search fail for Yoruba, Igbo, Hausa, Pidgin. | ASR captions and transcript search per language. | Published WER per language, failures included |

We don't claim "better than TikTok." We claim measured numbers.
Full reasoning: [VISION.md](VISION.md).

## Architecture at a glance

A Spring Boot **modular monolith**. Module boundaries are verified by
Spring Modulith in the build; video bytes never touch the app.

```mermaid
flowchart LR
  C[Next.js test client] -->|/v1 REST| API
  subgraph API[Spring Boot modular monolith]
    identity --- video
    video --- feed
    feed --- social
    social --- engagement
    moderation
    shared[(shared: job queue, errors, ids)]
  end
  C -->|presigned PUT/GET| S3[(MinIO / S3)]
  API --> PG[(PostgreSQL)]
  API --> R[(Redis)]
  PG -. job table, SKIP LOCKED .-> W[Workers: FFmpeg, ASR]
  W --> S3
```

Key decisions, each with rejected alternatives:

- [ADR 0001](docs/adr/0001-license-apache-2.md): Apache-2.0
- [ADR 0002](docs/adr/0002-spring-modulith-for-module-boundaries.md): Spring Modulith for module boundaries and events
- [ADR 0003](docs/adr/0003-postgres-backed-job-queue.md): one shared PostgreSQL-backed job queue
- [ADR 0004](docs/adr/0004-asr-evaluation-datasets.md): how ASR quality is measured
- [ADR 0005](docs/adr/0005-database-structure.md): database structure; see the [draft schema](docs/schema.md)
- [ADR 0006](docs/adr/0006-maven-build.md): Maven build
- [ADR 0007](docs/adr/0007-per-module-migrations.md): one Flyway run per module
- [ADR 0008](docs/adr/0008-job-queue-semantics.md): job queue delivery, leases and retries
- [ADR 0009](docs/adr/0009-idempotency-keys.md): safe retries with `Idempotency-Key`
- [ADR 0010](docs/adr/0010-persistence-with-jdbcclient.md): `JdbcClient`, not JPA
- [ADR 0011](docs/adr/0011-authentication.md): Argon2id passwords and short-lived JWTs
- [ADR 0012](docs/adr/0012-refresh-tokens-and-sessions.md): rotating refresh tokens, reuse detection, logout
- [ADR 0013](docs/adr/0013-rate-limiting.md): rate limiting for login and registration
- [ADR 0014](docs/adr/0014-transparent-ranker.md): a pluggable ranker whose score is its own explanation
- [ADR 0015](docs/adr/0015-feed-pipeline.md): the feed pipeline: pooled ranking, stable cursors, events

## Engineering standards

- Versioned `/v1` API, RFC 9457 errors, cursor pagination, `Idempotency-Key`, correlation IDs
- 3NF schemas, Flyway-only migrations, opaque public IDs
- Rotating refresh tokens with reuse detection; audited moderation actions
- Tests for failure paths; concurrency proven with Testcontainers
- Every ticket ends with updated [DESIGN.md](DESIGN.md), including *what it does not handle*

Details: [CONTRIBUTING.md](CONTRIBUTING.md).

## Stack

Java 25, Spring Boot 4.1, Spring Modulith, PostgreSQL, Redis, MinIO,
FFmpeg, Testcontainers, GitHub Actions. A thin Next.js + TypeScript
client in [web/](web/) serves as the test harness; the backend is the product.

## Documentation

| Document | Purpose |
|---|---|
| [VISION.md](VISION.md) | Why this exists and how we judge it |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Ordered tickets and milestones |
| [DESIGN.md](DESIGN.md) | Current architecture and its limits |
| [docs/adr/](docs/adr/) | Architecture decision records |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Rules and Definition of Done |
| [SECURITY.md](SECURITY.md) | Reporting vulnerabilities |
| [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) | Community standards |

## Getting started

Requires JDK 25. No other tool is needed; the Maven Wrapper downloads Maven.

```bash
docker compose up -d   # PostgreSQL 17 for local development
./mvnw verify          # build, run tests (needs Docker), verify module boundaries
./mvnw spring-boot:run # start the app, then GET /actuator/health
```

Tests start their own throwaway PostgreSQL with Testcontainers, so they
need Docker but not `docker compose`. Without Docker, point them at an empty,
disposable PostgreSQL 17 database with `PROTIK_TEST_DB_URL` (and `PROTIK_TEST_DB_USER`,
`PROTIK_TEST_DB_PASSWORD`); tests delete data in it. The API spec is served at
`/v3/api-docs`.

**Try it:** with the database and backend running, start the test harness UI
(`cd web && npm install && npm run dev`, then open http://localhost:3000) to register,
log in, call `/v1/me`, and see every request's status, correlation ID and error document.

**Sample videos:** there is no upload yet, so to see the feed start the backend with
`--protik.demo.seed=true`. This loads 48 sample entries into the feed's read model only (no real videos); it is
off by default and must never be enabled in production.

**What works today:** registration and login (Argon2id, 15-minute JWTs), rotating refresh tokens with
theft detection, logout, login and registration rate limiting, a transparent feed (`GET /v1/feed`: ranked by your own modes
and weights, each video explained, stable paging), idempotent retries,
RFC 9457 errors, request correlation IDs, OpenAPI, the shared job queue. There is no video
feature yet; see the [roadmap](docs/ROADMAP.md).

## License

[Apache-2.0](LICENSE).
