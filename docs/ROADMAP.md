# Roadmap

One ticket at a time, each finished to the Definition of Done in
[CONTRIBUTING.md](../CONTRIBUTING.md). Order is deliberate: the
foundations that other work depends on come first. Nothing below is
done yet.

## Milestone 0: Foundations
- [x] T0.1 Maven build, Spring Boot skeleton, CI, Modulith boundary
      verification ([ADR 0006](adr/0006-maven-build.md)). Testcontainers
      moved to T0.2: T0.1 has no database code, so it would be an unused
      dependency.
- [x] T0.2 Testcontainers, per-module Flyway baseline
      ([ADR 0007](adr/0007-per-module-migrations.md)), correlation IDs,
      RFC 9457 error handling, OpenAPI generation, docker-compose for
      PostgreSQL (Redis and MinIO join when a ticket uses them)
- [x] T0.3 Shared job queue ([ADR 0003](adr/0003-postgres-backed-job-queue.md),
      [ADR 0008](adr/0008-job-queue-semantics.md)) with concurrency tests

- [x] T0.4 `Idempotency-Key` support ([ADR 0009](adr/0009-idempotency-keys.md)), added because
      registration and upload both need it

## Milestone 1: Identity and upload
- [x] T1.1 Registration, Argon2 password hashing, JWT access tokens
      ([ADR 0010](adr/0010-persistence-with-jdbcclient.md), [ADR 0011](adr/0011-authentication.md))
- [x] T1.2 Rotating refresh tokens with family revocation on reuse
      ([ADR 0012](adr/0012-refresh-tokens-and-sessions.md))
- [ ] T1.5 Login hardening before any public deployment: per-account and per-IP rate limiting
      and lockout (Argon2 is memory-heavy, so unbounded attempts are a DoS vector), breached-password
      check, email verification, password reset
- [ ] T1.3 Presigned upload to MinIO, validation, `Idempotency-Key`.
      Upload safety (an uploaded file is hostile until proven otherwise):
      size and type limits at presign time; a quarantine bucket that
      nothing serves; malware scan (ClamAV) before a file is released;
      content sniffing that ignores the client-declared type; files are
      never executed or served from the app's own origin
- [ ] T1.4 Transcode job: 720p/360p, 144p, audio-only; measure storage
      and compute cost per rendition
      FFmpeg runs sandboxed: separate non-root container, no network,
      CPU/memory/time limits, read-only input, and output re-encoded from
      scratch (the original bytes are never republished)

## Milestone 2: Transparent feed
- [ ] T2.1 `Ranker` interface and structured score components
- [ ] T2.2 Ranking modes and user-adjustable weights
- [ ] T2.3 Cursor-paginated feed; p99 latency measured

## Milestone 3: Data saver
- [ ] T3.1 Per-user monthly data budget and prefetch cap
- [ ] T3.2 Reproducible MB-per-10-minutes benchmark, on and off

## Milestone 4: African-language captions
- [ ] T4.1 Evaluation protocol and test sets (ADR 0004)
- [ ] T4.2 Captions table and ASR job step; baseline WER per language
- [ ] T4.3 Transcript search

## Milestone 5: Social, engagement, moderation
- [ ] T5.1 Follows, likes, comments; counters with accuracy-under-load
      test
- [ ] T5.2 Reporting, takedown, audit log, account deletion that deletes

## Frontend harness
- [x] Thin Next.js client: register (with idempotent retry), log in, call `/v1/me`, and a request
      log showing status, correlation ID, replay header and problem documents. Grows with each ticket
      that adds an endpoint worth exercising.

## Supply chain (continuous)
- [x] Dependabot for Maven and GitHub Actions
- [ ] After the first push: enable GitHub secret scanning and push
      protection, add dependency review on pull requests and CodeQL
      (verify CodeQL supports Java 25 before relying on it)
- [ ] Pin GitHub Actions to commit SHAs
- [ ] Image scanning for container builds when a Dockerfile exists

## Before any public deployment
- [ ] Legal review (NDPA, child safety, copyright procedure)
- [ ] Privacy notice and terms
