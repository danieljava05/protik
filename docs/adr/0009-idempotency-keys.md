# ADR 0009: Idempotency-Key support

- Status: accepted
- Date: 2026-10-18

## Context

CONTRIBUTING.md requires `Idempotency-Key` on every non-idempotent POST.
On mobile networks a client often cannot tell whether a request that
timed out was processed; retrying must not create a second account, a
second upload or a second comment. The table `shared.idempotency_keys`
already exists (ADR 0005).

## Options considered

### A. Servlet filter that records the finished response
Generic, no change to controllers. But the key is written in a separate
transaction after the business transaction commits. A crash between the
two leaves the work done and the key missing, so the retry does the work
a second time. It also needs an "in flight" state and a policy for
concurrent duplicates.

### B. Reserve the key in the same transaction as the work
The service claims the key with `INSERT ... ON CONFLICT`, runs the
business action, stores the response, and commits once. Either all three
happen or none do. A concurrent duplicate waits on the unique index until
the first transaction ends, then replays its result; no in-flight state
exists in committed data.

### C. Rely on natural uniqueness only
Unique usernames, etc. already reject duplicates, but with a 409 instead
of the original success response, and not at all for actions without a
natural key (comments, uploads).

## Decision

B. `IdempotencyService.execute(scope, key, endpoint, fingerprint, ...)`
wraps the business action. Rules:

- The key is optional. Without the header the action simply runs.
- A key is 1 to 255 printable ASCII characters, otherwise 400.
- Keys are scoped to the caller (`user_id`); anonymous endpoints use the
  nil UUID scope.
- Same key with a different endpoint or request fingerprint: 422, never a
  silent replay of a different request's result.
- Only successful responses are stored. A failed action rolls back,
  including the key, so a retry runs it again.
- Replays return the original status and body plus `Idempotent-Replayed: true`.
- Keys expire after 24 hours (a reused expired key is a fresh key); a
  periodic purge deletes expired rows.
- The request fingerprint is an **HMAC-SHA-256 with a server secret**, not
  a plain hash. Request bodies can contain passwords, and an unkeyed hash
  stored in the database could be brute-forced offline.

## Deliberate exception: credential-bearing responses

Endpoints whose response contains secrets (login and token refresh) do
**not** use idempotency keys. Replaying them would require storing tokens
in the database. A retried login just creates another session, which is
harmless. This departs from the letter of the "every non-idempotent POST"
rule and is recorded here as a conscious decision.

## Consequences

- Controllers call the service explicitly; they do not get it for free.
  Forgetting it on a new POST is a review item (the PR template checks it).
- Because the claim and the work share a transaction, a long action holds
  a row lock on the key, and concurrent duplicates wait for it.
- Anonymous scope is shared: two different clients that pick the same key
  collide (the second gets 422). Clients must use random keys (UUIDs).
- The stored response body is JSON; it must never contain secrets.

## Why the alternatives were rejected

A can duplicate work after a crash, which is exactly the failure
idempotency keys exist to prevent. C protects only some actions and
reports a duplicate as an error.
