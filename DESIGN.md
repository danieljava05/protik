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
| Schema per module, UUIDv7 IDs, no cross-module FKs, event-fed counters and read models | [0005](docs/adr/0005-database-structure.md) |

## Schema

Logical model: [docs/schema.md](docs/schema.md). Validated SQL draft:
[docs/schema-draft/](docs/schema-draft/), tested on PostgreSQL 17 (the
tests prove constraints and structure, not performance). Each module
owns one PostgreSQL schema; a catalog test already forbids cross-schema
foreign keys. Nothing is migrated through Flyway yet.

## Deliberate denormalizations

Planned (D1 to D5: caption search column, feed candidate read model,
candidate stats, cached counters, rendered VTT). Each is named with its
consistency mechanism in
[docs/schema.md](docs/schema.md#deliberate-denormalizations).

## What this does not handle

Almost everything: only the skeleton exists, with no features, no
database code and no security. Known gaps decided in advance:

- Database-level module isolation is by schema separation plus a test,
  not by per-module database roles (ADR 0005, upgrade path).
- No cross-module referential integrity; orphans are handled by events
  and reconciliation.
- Pidgin ASR has no confirmed standard benchmark (ADR 0004).
- Legal review, privacy notice and terms are not done; the service must
  not be publicly deployed before them.

## Rejected alternatives

Recorded in the relevant ADR.
