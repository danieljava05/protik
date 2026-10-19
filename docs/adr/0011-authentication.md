# ADR 0011: Authentication: Argon2id passwords and short-lived JWT access tokens

- Status: accepted
- Date: 2026-10-18

Rotating refresh tokens (session families, reuse detection) are in
[ADR 0012](0012-refresh-tokens-and-sessions.md). This one covers registration, login and access
tokens.

## Context

VISION.md and CONTRIBUTING.md: never invent cryptography; Argon2 or bcrypt for
passwords; standard JWT/HMAC libraries for tokens. Users are on mobile data,
so tokens must be small and requests cheap, but a stolen token must stop
working quickly.

## Decisions

### 1. Password hashing: Argon2id via Spring Security

`Argon2PasswordEncoder` (BouncyCastle implementation), parameters at the
OWASP minimum for Argon2id: 19 MiB memory, 2 iterations, parallelism 1,
16-byte salt, 32-byte hash. The encoded string carries its own parameters,
so they can be raised later and old hashes still verify.
*Rejected: bcrypt* (still acceptable, but 72-byte input limit and no memory
hardness). *Rejected: PBKDF2* (weaker against GPUs).

Cost: each hash uses about 19 MiB and some tens of milliseconds. Unbounded
login attempts are therefore a denial-of-service vector; rate limiting is
described in [ADR 0013](0013-rate-limiting.md).

### 2. Password policy

10 to 128 characters (the upper bound stops a huge password being used to burn
CPU), and not equal to the username or email. No composition rules (NIST
SP 800-63B discourages them). A breached-password check is not implemented
yet and is listed as a gap.

### 3. Access token: signed JWT, HS256, 15 minutes

Spring Security's Nimbus JOSE integration; the decoder is pinned to HS256, so
tokens with `alg: none` or another algorithm are rejected. Claims: `iss`,
`sub` (account UUID), `iat`, `exp`, `jti`. The secret is at least 32 bytes
from `JWT_SECRET`; if unset, a random secret is generated at startup with a
warning, so tokens do not survive a restart and multi-instance
misconfiguration fails loudly instead of using a guessable default.

*Rejected: asymmetric keys (ES256/RS256) for now.* Issuer and verifier are the
same application, so a shared secret is no weaker than a private key held in
the same place, and avoids key-file handling. Moving to asymmetric keys, with
a `kid` header for rotation, is the path if a second service must verify
tokens. *Rejected: opaque tokens with a lookup table:* a database hit on every
request. *Rejected: long-lived access tokens:* a theft would last too long;
revocation of a session happens through refresh tokens (T1.2).

### 4. Stateless API, no CSRF protection

Tokens travel in the `Authorization` header, never in cookies, so there is no
ambient credential for a cross-site request to ride on. CSRF protection is
therefore disabled deliberately. If cookies are ever used for tokens, this
must be revisited.

### 5. Login does not reveal which part was wrong

Unknown account and wrong password return the same 401 body. For an unknown
account the server still verifies the password against a dummy hash so the
response time does not distinguish the two. Registration does reveal that a
username or email is taken (409); a username-based service cannot avoid that
without email verification.

### 6. Errors from the security layer are problem documents

401 and 403 are `application/problem+json` with the correlation ID, like every
other error, and 401 carries `WWW-Authenticate: Bearer`.

## Consequences

- Login is exempt from idempotency keys (ADR 0009): its response holds a token.
- No email verification and no password reset yet; `verified_at` stays null.
- The JWT secret is a high-value secret. Rotating it logs everyone out, which is
  acceptable at this stage.
- CORS is limited to configured origins (default: the local frontend).

## What this does not handle
- Breached-password check, email verification,
  password reset, multi-factor authentication, token revocation before expiry
  (until T1.2), asymmetric keys.
