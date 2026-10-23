# Roadmap

One ticket at a time, each finished to the Definition of Done in
[CONTRIBUTING.md](../CONTRIBUTING.md). Order is deliberate: the
foundations that other work depends on come first. Ticked items are
done and tested; what each one still does not handle is listed in
[DESIGN.md](../DESIGN.md).

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
- [x] T1.5 Rate limiting for login and registration ([ADR 0013](adr/0013-rate-limiting.md))
- [x] T1.6 Account hardening: common-password check, email verification, password reset
      ([ADR 0021](adr/0021-account-hardening.md)). **Still open: a real email provider, so nobody receives mail yet.**
- [x] T1.3 Upload with validation, `Idempotency-Key`, quarantine, malware scan and content sniffing
      ([ADR 0022](adr/0022-uploads-and-transcoding.md)). **Built as a streamed upload to a local file
      store, not a presigned upload to MinIO** (no S3 here to test it); the storage is a port. The ClamAV
      client is tested against a fake daemon locally and against a real one only in CI.
- [x] T1.4 Transcode job: 144p, 240p, 480p, 720p (never above the source) and audio-only, as progressive MP4
      with a thumbnail, using the real FFmpeg. **Not a sandbox**: production needs a container with no network
      (see DESIGN.md). Storage and compute cost per rendition are measured in T3.2.
      Open: S3/MinIO adapter, HLS, resumable uploads.

## Milestone 2: Transparent feed
- [x] T2.1 `Ranker` interface and structured score components ([ADR 0014](adr/0014-transparent-ranker.md))
- [x] T2.2 Ranking modes and user-adjustable weights (`/v1/feed/modes`, `/v1/feed/preferences`)
- [x] T2.3 Cursor-paginated feed; p99 latency measured ([ADR 0015](adr/0015-feed-pipeline.md))

## Milestone 3: Data saver
- [x] T3.1 Per-user monthly data budget and prefetch cap ([ADR 0018](adr/0018-data-budget.md))
- [x] T3.2 Reproducible MB-per-10-minutes benchmark, on and off ([results and caveats](benchmarks/README.md)).
      Measured on one animated clip only; a representative clip set is still to do.

## Milestone 4: African-language captions
- [ ] T4.1 Evaluation protocol and test sets (ADR 0004)
- [ ] T4.2 Captions table and ASR job step; baseline WER per language
- [ ] T4.3 Transcript search

## Milestone 5: Social, engagement, moderation
- [x] T5.1 Follows, likes, comments; counters with accuracy-under-load test
      ([ADR 0016](adr/0016-social-and-engagement.md), [ADR 0017](adr/0017-comments.md))
- [x] T5.2 Reporting, takedown, audit log, account deletion that deletes
      ([ADR 0019](adr/0019-moderation.md), [ADR 0020](adr/0020-account-deletion.md))

## Frontend harness
- [x] Thin Next.js client (now also ranking settings, the feed with score bars, like/follow/comment): register (with idempotent retry), log in, call `/v1/me`, and a request
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
