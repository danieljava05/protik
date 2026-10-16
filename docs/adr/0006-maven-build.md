# ADR 0006: Maven as the build tool

- Status: accepted
- Date: 2026-10-17

## Context

Ticket T0.1 needs a build for a Java 25 / Spring Boot 4.1 modular
monolith: dependency management, test execution, a reproducible
wrapper, and CI. Spring Boot 4.1.1 and Spring Modulith 2.1.1 resolve
from Maven Central (checked at start.spring.io and by a successful
build).

## Options considered

### A. Maven (with the Maven Wrapper)
Declarative `pom.xml`, the Spring Boot parent supplies plugin and
dependency management, one obvious way to do things. Reviewers read it
without learning a build language. Slower incremental builds and no
built-in build cache.

### B. Gradle (Kotlin DSL)
Faster incremental builds and a build cache. A programmable build
invites custom logic, and Kotlin DSL files are harder to review for a
project whose product is the backend, not the build.

## Decision

Maven with the wrapper (`./mvnw`), Spring Boot parent POM, Spring
Modulith BOM. Versions are pinned in `pom.xml`; Dependabot proposes
updates weekly.

## Consequences

- Anyone with a JDK 25 builds with `./mvnw verify`; no global Maven.
- If build time becomes a measured problem (not yet: no module is
  large), revisit with numbers.
- Note for readers of other tutorials: the Spring initializr lists Boot
  versions with a `.RELEASE` suffix in its metadata, but Maven Central
  uses plain `4.1.1`. (Older 2.x versions used `.RELEASE`.)

## Why the alternative was rejected

Gradle's speed advantage does not matter at this size, and its
flexibility is a cost for a project meant to be easy to audit.
