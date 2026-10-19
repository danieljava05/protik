# ADR 0010: Persistence with Spring's JdbcClient, not JPA

- Status: accepted
- Date: 2026-10-18

## Context

CONTRIBUTING.md says "never return JPA entities from the API", which assumed
JPA. The schema (ADR 0005, ADR 0007) is designed first and leans on
PostgreSQL features: `citext`, partial and expression indexes, generated
columns, `FOR UPDATE SKIP LOCKED`, `ON CONFLICT`, partitioned tables,
triggers. Each module owns its schema and may not join across modules.

## Options considered

### A. JPA / Hibernate
Familiar, entity mapping, derived queries. But the schema, not the object
model, is the source of truth here; entities would be a second, lossy
description of it. Lazy loading, dirty checking and the persistence
context add behaviour that is hard to reason about in a concurrency-sensitive
design (claims, idempotency, fencing all rely on exact SQL). Native queries
would be needed for most of the interesting statements anyway.

### B. Spring Data JDBC
Lighter than JPA, aggregate-based. Still generates SQL we do not control and
maps poorly onto features like `RETURNING` with conditional updates.

### C. `JdbcClient` with explicit SQL
What the job queue and idempotency store already use. Every statement is
visible, reviewable and testable against the real database. Mapping is
written by hand.

## Decision

C. Each module has repository classes with explicit SQL in its internal
package; services own transactions; controllers see only DTOs. The
"no entities in the API" rule is satisfied trivially because there are no
entities.

## Consequences

- More hand-written mapping code, but no hidden queries or lazy-loading
  surprises, and SQL features are used directly.
- No schema validation by an ORM; `SchemaTests` and each repository's tests
  are the safety net.
- If a module later has a rich object graph where an ORM clearly pays off,
  it can adopt one behind its own repository without affecting others.
- Spring Boot's JDBC transaction manager is the only one in play.

## Why the alternatives were rejected

A and B add an abstraction between us and the SQL that the design depends on,
for a codebase whose queries are mostly small and specific.
