# ProTik Design

Living document. Updated with every ticket.

## Current architecture

Modular monolith (Spring Boot) with modules `identity`, `video`,
`feed`, `social`, `engagement`, `moderation`, `shared`. No application
code exists yet. Decisions so far:

| Decision | ADR |
|---|---|
| Apache-2.0 license | [0001](docs/adr/0001-license-apache-2.md) |
| Spring Modulith verifies boundaries; events between modules | [0002](docs/adr/0002-spring-modulith-for-module-boundaries.md) |
| One PostgreSQL-backed job queue (`SKIP LOCKED`) | [0003](docs/adr/0003-postgres-backed-job-queue.md) |
| ASR measurement protocol (proposed) | [0004](docs/adr/0004-asr-evaluation-datasets.md) |

## Schema ownership

Each table belongs to exactly one module; no other module queries it.
Modulith does not enforce this at the database level (see ADR 0002), so
this table is checked in review. Populated as tables are created.

| Module | Tables |
|---|---|
| _(none yet)_ | |

## Deliberate denormalizations

None yet. Each one must be named here with its consistency mechanism.

## What this does not handle

Everything. Nothing is implemented. Known gaps decided in advance:

- Database-level module isolation is by convention, not enforced.
- Pidgin ASR has no confirmed standard benchmark (ADR 0004).
- Legal review, privacy notice and terms are not done; the service must
  not be publicly deployed before them.

## Rejected alternatives

Recorded in the relevant ADR.
