# ADR 0001: License the project under Apache-2.0

- Status: accepted
- Date: 2026-10-17

## Context

The repository is public. Without a license, the code is legally "all
rights reserved" and nobody, including employers evaluating it, can
safely read, run or build on it. The project also depends on
Apache-2.0 and MIT ecosystems (Spring, FFmpeg usage via subprocess).

## Options considered

### Apache-2.0
Permissive, explicit patent grant, widely accepted by companies.
Anyone may run a closed hosted fork.

### MIT
Permissive and shorter, but no patent grant.

### AGPL-3.0
Network copyleft: hosted forks must publish changes. Protects openness
of the service, but many companies forbid AGPL code, which limits
reuse and contributions.

## Decision

Apache-2.0.

## Consequences

Maximum reuse and contributor comfort. We accept that a third party can
host a closed fork. Contributions are accepted under the same license.

## Why the alternatives were rejected

MIT lacks a patent grant. AGPL's reuse restrictions conflict with the
goal of the code being easy to study and adopt; the project's value is
the measured design, not exclusive hosting rights.
