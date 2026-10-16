# ADR 0005: Database structure and conventions

- Status: proposed
- Date: 2026-10-17

The logical model is in [docs/schema.md](../schema.md); the validated
SQL is in [docs/schema-draft/](../schema-draft/). This ADR records the
structural decisions behind it.

## Context

One PostgreSQL database serves a Spring Modulith monolith (ADR 0002).
Requirements from VISION.md and CONTRIBUTING.md: 3NF, named
denormalizations only, Flyway-only changes, opaque public IDs,
cursor pagination, account deletion that really deletes, captions as a
first-class table, and no module reading another module's tables.

ADR 0002 left a gap: Modulith does not stop a module querying another
module's tables. This ADR closes it as far as is practical.

## Decision 1: how modules are isolated in the database

### Options
- **A. One shared `public` schema, naming prefixes.** Simplest;
  isolation is convention only.
- **B. One PostgreSQL schema per module** (`identity`, `video`, `feed`,
  `social`, `engagement`, `moderation`, `shared`), Flyway migrations
  per module, **no foreign keys across schemas**.
- **C. B plus a database role and connection pool per module**, with
  grants limiting each role to its own schema.

### Decision
B now; C is a documented upgrade path. A test scans every JPA entity
and migration and fails if a table is referenced outside its own
schema. Cross-module references are plain ID columns.

### Why not the others
A leaves the ADR 0002 gap fully open. C gives hard enforcement but
costs several pools and more operational burden for a solo project
with no measured need.

### Consequences
- No cross-module referential integrity from the database. Orphans
  (for example a like for a deleted video) are prevented by events and
  detected by a scheduled reconciliation job; both are tested.
- Cross-module reads go through a module's public API, so some screens
  need an extra call or a local read model (Decision 4).
- A module can be extracted later without untangling foreign keys.

## Decision 2: identifiers

### Options
- **A. `bigint` identity PK plus a separate random `public_id`.**
  Compact joins; two identifiers everywhere; risk of leaking the
  internal one.
- **B. UUIDv7 as the only ID**, generated in the application.
- **C. Random UUIDv4 PK.** Opaque, but random inserts fragment indexes.

### Decision
B. UUIDv7 is time-ordered, which keeps B-tree inserts mostly
sequential, and it is not guessable or enumerable. Generated in the
app, so it does not depend on the PostgreSQL version. Cursors use
`(sort_key, id)` keyset pagination.

### Consequences
UUIDv7 embeds a creation timestamp (millisecond precision), so an ID
reveals when a row was created. Accepted: video and comment times are
public anyway. Identifiers are 16 bytes instead of 8; every foreign
key and index is larger. This cost is measured at T0.2, not assumed.

### Why not the others
A doubles the identifiers and invites mistakes; C costs write
performance for no benefit over B.

## Decision 3: counters and other derived data

Cached counters are the main source of denormalization. The choice is
how `engagement.video_counter` stays correct under concurrent likes.

### Options
- **A. `UPDATE ... SET likes = likes + 1` in the same transaction as
  the like row.** Always consistent, but a viral video becomes a hot
  row and writers queue on its lock.
- **B. Redis increments, flushed periodically to PostgreSQL.** Fast;
  counts can be lost or drift on crash.
- **C. Source rows are the truth; counters are updated asynchronously
  from events in batches, and a reconciliation job recomputes them
  from source.** Counters are eventually consistent but self-healing.

### Decision
C, starting with batched `UPDATE`s. Redis (B) is introduced only if a
load test shows the database path is the bottleneck. Every counter has
a source table and a recompute query; the test "counter accuracy under
load" compares the two.

### Why not the others
A contends on hot rows; B accepts silent drift that the vision's
"counter accuracy" metric would expose.

## Decision 4: feed data without reading other modules' tables

`feed` needs candidate videos and their stats but may not read `video`
or `engagement` tables.

### Options
- **A. Call `video` and `engagement` APIs per feed request.** Always
  fresh; adds latency and many calls on the hottest path.
- **B. `feed` keeps its own read model** (`feed.candidate`,
  `feed.candidate_stats`), fed by events and rebuildable from the
  owning modules' APIs.

### Decision
B. These are deliberate denormalizations, named in DESIGN.md. They are
disposable: if wrong, rebuild them. Staleness is bounded and measured.

### Why not A
It puts N cross-module calls inside the p99 latency the project
promises to publish.

## Decision 5: account deletion

"Account deletion must actually delete" must hold across schemas
without cross-module foreign keys.

### Decision
`identity` records a `deletion_request` and publishes
`UserDeletionRequested`. Each module deletes the user's data and
records an acknowledgement. The account is purged from `identity` only
after every module acknowledges, and unfinished requests are surfaced.
Moderation audit entries are kept but hold only a pseudonymous actor ID
with no personal data; the legal retention basis is documented before
launch. No generic soft-delete: rows use explicit status values.

### Why not cascading foreign keys
They would work in one schema but reintroduce the cross-module
coupling Decision 1 removes.

## Conventions
- Timestamps are `timestamptz`, UTC.
- Text search uses the `simple` configuration with `unaccent` and
  `pg_trgm`. PostgreSQL ships no Yoruba, Igbo, Hausa or Pidgin
  stemmers, and diacritics matter (Yoruba tone marks); how to treat
  them is decided with the ASR protocol (ADR 0004).
- Language is stored as a BCP 47 / ISO code (`yo`, `ig`, `ha`, `pcm`,
  `en`). `pcm` is the ISO 639-3 code for Nigerian Pidgin.
- Every index names the query it serves in its migration comment.
- Large append-only tables (`view_event`, `usage_event`) are
  partitioned by month so old data can be dropped cheaply.

## What this does not handle
- Hard database-level module isolation (upgrade path C).
- Multi-region or read replicas.
- Verified storage-size and query-plan numbers: none measured yet.
