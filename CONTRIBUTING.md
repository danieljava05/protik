# Contributing to ProTik

Thanks for your interest. ProTik is pre-alpha; read [VISION.md](VISION.md)
first. These rules apply to every contributor, human or AI.

## 1. Study the architecture before building

- Before implementing a ticket, review the area you touch: read the
  existing modules, schema and [DESIGN.md](DESIGN.md). Lay out at least
  two viable designs, compare trade-offs (consistency, latency, cost,
  operational burden, failure modes), and choose one with written
  reasons.
- Pick the best design for the problem, not the quickest or most
  fashionable. If the best design is more work, say so and plan it.
- Record every significant decision as an ADR in [docs/adr/](docs/adr/):
  context, options considered, decision, consequences, and why the
  alternatives were rejected.
- Structure matters as much as function. A feature that works but sits
  in the wrong module, leaks an abstraction, or breaks a boundary is
  not done.

## 2. Architecture rules

- **Modular monolith** in Spring Boot. Modules: `identity`, `video`,
  `feed`, `social`, `engagement`, `moderation`, `shared`.
- No module reads another module's tables or internal classes. Modules
  talk through a public API or published events only. Enforced by
  Spring Modulith verification (ADR 0002), plus ArchUnit rules where
  needed. Violations fail the build.
- No microservices without a measured, documented reason.
- Inside a module: controller -> service -> repository. No business
  logic in controllers. Never return JPA entities from the API; use
  DTOs. Transaction boundaries live in the service layer and are
  explicit.
- The feed is built on a pluggable `Ranker` interface. Scores are
  returned as structured components, never free-text strings.
- Long-running work (transcoding, captioning) goes through the one
  shared job queue. Do not build a second job system.
- Video bytes never pass through the Spring app and never live in
  PostgreSQL. Use presigned uploads to object storage.

## 3. Stack

- Java 25 (LTS) and Spring Boot 4.1. PostgreSQL, Redis, MinIO
  (S3-compatible), FFmpeg, Testcontainers, GitHub Actions.
- Where a pattern differs on older versions (Java 17/21, Spring Boot
  3.x), note it in a code comment or ADR.
- Frontend: a thin Next.js + TypeScript client. The backend is the
  product; the frontend is the test harness. Do not polish the UI.

## 4. Data rules

- Schemas in **3NF**. Any deliberate denormalization (cached counters,
  derived search columns) is named and justified in `DESIGN.md`, with a
  stated way to keep it consistent.
- Schema changes only through Flyway migrations. Every index is
  justified by a real query.
- Money, if ever introduced, is a `long` in kobo. Never floating point.
- Opaque public IDs in URLs. Never expose sequential database IDs.

## 5. API rules

- Versioned under `/v1`. OpenAPI spec generated and accurate.
- Errors use RFC 9457 Problem Details everywhere.
- Cursor-based pagination on any list that can grow. No offset
  pagination on feeds.
- `Idempotency-Key` supported on every non-idempotent POST.
- Correlation IDs on every request and in every log line.

## 6. Security and safety rules

- Never invent cryptography. Use vetted libraries (Argon2 or bcrypt for
  passwords, standard JWT/HMAC libraries for tokens).
- Refresh tokens rotate; reuse of a rotated token revokes the whole
  session family.
- Every state-changing moderation or admin action is audit-logged with
  actor, time, and reason.
- Uploaded media is untrusted input. Validate type and size at the
  presign step, and run FFmpeg isolated (no network, resource limits,
  non-root).
- Respect the Nigeria Data Protection Act (NDPA) for real user data.
  Account deletion must actually delete.
- Do not use any employer's models, data, or IP in this project.
- Report vulnerabilities privately; see [SECURITY.md](SECURITY.md).

## 7. Definition of Done (every ticket)

- Tests cover **failure paths**, not only happy paths. Concurrency
  claims are proven by a Testcontainers test, not by reasoning.
- CI is green on every push.
- Performance claims are **measured**, with hardware stated.
- `DESIGN.md` is updated, including a current "What this does not
  handle" section.
- Rejected alternatives are written down.

## 8. How to work

- **Challenge flaws.** If a ticket, schema or earlier decision is weak,
  say so plainly and propose a better design before building the weak
  one. Do not silently work around a bad decision.
- Do not claim something works unless it was run and tested. State what
  was and was not verified.
- If a requirement is ambiguous, state your assumption and continue.
  Ask only when the answer changes the architecture.
- Prefer small, reviewable changes. One ticket at a time, finished to
  the Definition of Done before the next begins.
- Do not add features, dependencies or modules beyond the ticket
  without justification.
