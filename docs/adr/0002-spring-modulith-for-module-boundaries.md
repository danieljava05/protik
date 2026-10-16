# ADR 0002: Spring Modulith for module boundaries and events

- Status: accepted
- Date: 2026-10-17

## Context

ProTik is a modular monolith with modules `identity`, `video`, `feed`,
`social`, `engagement`, `moderation`, `shared`. The rule is: no module
reads another's tables or internals; modules talk through a public API
or published events. The rule must fail the build when broken.

Spring Modulith 2.0 went GA in November 2025 with a Spring Boot 4 /
Spring Framework 7 baseline
([release post](https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released/)).

## Options considered

### A. ArchUnit only
Hand-written rules per package. Flexible, but we write and maintain the
module model ourselves, and it gives no event infrastructure.

### B. Spring Modulith (with ArchUnit for extra rules)
Treats each top-level package as a module, verifies dependencies and
cycles in a test, generates module documentation, and provides
`@ApplicationModuleListener` plus a persistent event publication
registry so events survive crashes (at-least-once delivery).

### C. Separate Maven/Gradle modules per bounded context
Compiler-enforced boundaries, but heavy build overhead for a solo
project and no event support.

## Decision

Option B. Module structure is verified in the test suite; cross-module
side effects use Modulith events. ArchUnit stays available for rules
Modulith does not express (for example "controllers contain no
business logic", "no JPA entities in API signatures").

## Consequences

- Event listeners must be idempotent (at-least-once delivery).
- Modules expose only types in their base package; internals live in
  sub-packages.
- Database-level isolation (no cross-module joins) is **not** enforced
  by Modulith. It is enforced by review and by a schema-ownership table
  in `DESIGN.md`. This is a known gap.
- Modulith's version must track the Spring Boot version.

## Why the alternatives were rejected

A reinvents what Modulith verifies and provides no event registry. C
adds build complexity before a measured need; if a module must be
extracted later, Modulith's boundaries are the natural seam.
