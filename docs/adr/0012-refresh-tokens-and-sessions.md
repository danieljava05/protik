# ADR 0012: Refresh tokens, rotation and reuse detection

- Status: accepted
- Date: 2026-10-19

Extends [ADR 0011](0011-authentication.md). Access tokens last 15 minutes; a session
is kept alive with a refresh token. A stolen refresh token must not give an attacker
a quiet, permanent session, and a legitimate user on a poor connection must not be
logged out by a dropped response.

## Context

CONTRIBUTING.md: "Refresh tokens rotate, and reuse of a rotated token revokes the
whole session family." The schema already has `session_families` and
`refresh_tokens` (ADR 0005). Users are on mobile data, where a request can succeed on
the server while the response is lost.

## Decisions

### 1. Opaque random tokens, stored only as a hash

A refresh token is 256 bits from `SecureRandom`, URL-safe base64, with the prefix
`ptk_rt_` so secret scanners can recognise a leak. The database stores only its
SHA-256. A fast hash is correct here, unlike for passwords: the input has 256 bits of
entropy, so there is nothing to brute-force, and the lookup must be cheap. A database
leak therefore reveals no usable token.

*Rejected: refresh tokens as JWTs.* They would be self-describing but could not be
revoked without a lookup anyway, and a longer-lived signed token is a bigger prize.
*Rejected: tokens derived from the family ID with an HMAC.* That would let a lost
response be answered with the same successor token, but anyone obtaining the server
secret could mint tokens for any session; a home-made derivation scheme also sits too
close to "inventing cryptography".

### 2. Rotation: one active token per session

Each use of a refresh token marks it rotated and issues a new one. At any moment a
session (family) has exactly one unrotated token. A token lives 30 days; the session
as a whole lives at most 90 days, after which the user logs in again.

### 3. Reuse detection

Presenting a token that was already rotated means two parties hold the same secret.
If that happens **more than the grace period** after the rotation (default 10 s), the
whole family is revoked (`TOKEN_REUSE`): the thief and the real user are both logged
out, and the user re-authenticates with their password. The revocation must be
committed even though the request fails, so the method opts out of rollback for the
error it throws.

### 4. A short grace period instead of instant revocation

Within the grace period a rotated token is rejected (401) **without** revoking the
family. This covers duplicate requests from the same client (retries, two tabs
refreshing at once): exactly one wins and the family survives. The cost: a client
whose response was lost holds a rotated token and must log in again. The window also
lets a thief who replays a token within 10 s of the real user get a 401 instead of
triggering revocation; they still receive no token.

*Rejected: no grace (revoke on any reuse).* A dropped response on a mobile network
would log users out. *Rejected: re-issuing a fresh token for reuse inside the grace
period.* It recovers lost responses but creates two live branches and weakens
detection; revisit if re-login after a lost response proves common.

### 5. Serialising concurrent refreshes

The family row is locked (`SELECT ... FOR UPDATE`) before the token state is read, so
simultaneous requests for one token are processed one after another and exactly one
rotates it. Different families never block each other.

### 6. Failures are indistinguishable

Unknown, malformed, expired, rotated, revoked and wrong-account-state tokens all
return the same 401 problem (`invalid-refresh-token`), so the response teaches an
attacker nothing. The reason is logged server-side. All time comparisons use the
database clock.

### 7. Logout

`POST /v1/auth/logout` revokes the session of the refresh token presented and always
answers 204 (it does not reveal whether the token existed). `POST /v1/auth/logout-all`
(authenticated) revokes every session of the account. Access tokens already issued
remain valid until they expire (at most 15 minutes) because they are stateless; each
carries a `sid` claim naming its session for future use.

### 8. Not idempotency-keyed

Like login (ADR 0009), refresh responses contain credentials and are not replayable
from the database.

## Consequences

- A refresh costs one transaction with a row lock and three small statements.
- Revoked and old sessions are purged by a scheduled job (30 days after revocation or
  expiry), keeping the tables small.
- Suspending an account revokes its sessions when they next refresh; access tokens
  outlive that by up to 15 minutes.
- Rate limiting of `/refresh` and `/login` is still missing (roadmap T1.5).

## What this does not handle
Device names and "active sessions" listing, geo or device anomaly detection, binding
tokens to a device key, and instant (sub-15-minute) access-token revocation.
