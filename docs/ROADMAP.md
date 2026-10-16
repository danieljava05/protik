# Roadmap

One ticket at a time, each finished to the Definition of Done in
[CONTRIBUTING.md](../CONTRIBUTING.md). Order is deliberate: the
foundations that other work depends on come first. Nothing below is
done yet.

## Milestone 0: Foundations
- [ ] T0.1 Gradle/Maven build, Spring Boot skeleton, Testcontainers,
      CI (build + test + Modulith verification)
- [ ] T0.2 Flyway baseline, correlation IDs, RFC 9457 error handling,
      OpenAPI generation
- [ ] T0.3 Shared job queue (ADR 0003) with concurrency tests

## Milestone 1: Identity and upload
- [ ] T1.1 Registration, Argon2 password hashing, JWT access tokens
- [ ] T1.2 Rotating refresh tokens with family revocation on reuse
- [ ] T1.3 Presigned upload to MinIO, validation, `Idempotency-Key`
- [ ] T1.4 Transcode job: 720p/360p, 144p, audio-only; measure storage
      and compute cost per rendition

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

## Before any public deployment
- [ ] Legal review (NDPA, child safety, copyright procedure)
- [ ] Privacy notice and terms
