# ADR 0007: One Flyway run per module, each in its own schema

- Status: accepted
- Date: 2026-10-17

## Context

ADR 0005 gives every module its own PostgreSQL schema and forbids
foreign keys across them. CONTRIBUTING.md requires all schema changes
to go through Flyway migrations. We need a migration setup that matches
that ownership: a module's schema should be changed only by that
module's migrations, in a history that module controls.

Spring Boot's Flyway auto-configuration manages one Flyway instance with
one history table and one set of locations.

## Options considered

### A. One Flyway instance, one history table, many locations
All modules' migrations live in one version sequence. Simple, but every
version number is global: two modules cannot independently use `V2`,
migrations from different modules interleave in one history, and a
module cannot be extracted without untangling the shared history.

### B. One Flyway run per module, own schema and history table
Each module has `db/migration/<module>/` and a `flyway_schema_history`
table inside its own schema. Versions are per module. A module can be
reviewed, versioned and extracted on its own. Needs a small runner,
because Boot's auto-configuration cannot express it.

### C. Schema-less migrations with module-prefixed table names
No isolation at all; contradicts ADR 0005.

## Decision

B. `ModuleMigrations` (in the `shared` module) runs Flyway for each
module in a fixed order, with `shared` first because it installs the
extensions and helper functions the others use. Boot's auto-configured
Flyway is disabled. Each run sets `public` on the search path so the
extensions (`citext`, `unaccent`, `pg_trgm`, installed in `public`)
resolve. A test fails if the runner's module list differs from the
codebase's modules.

## Consequences

- Beans that need the schema (a future JPA `EntityManagerFactory`) must
  declare `@DependsOn("moduleMigrations")`; Boot's automatic ordering
  for its own Flyway does not apply. Recorded here so ticket T1.1 does
  not rediscover it.
- Startup runs seven small migrations; there is no measured startup cost
  and none is claimed.
- Concurrent application starts rely on Flyway's own locking; this is
  not yet tested with two instances.
- Extensions are installed in `public`, shared by all modules. The three
  chosen extensions are "trusted" in PostgreSQL 13+, so a database owner
  without superuser rights can create them. Verified only with the
  superuser of the test container.
- The Flyway runner lives in `shared`, which therefore knows module
  names as strings. Accepted; the drift test covers it.

## Why the alternatives were rejected

A forces a single global version space and a shared history, which is the
coupling ADR 0005 removes. C gives up isolation for no benefit.
