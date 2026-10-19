# ADR 0013: Rate limiting for login and registration

- Status: accepted
- Date: 2026-10-19

## Context

ADR 0011 left a known gap: nothing stops password guessing, and every login runs
Argon2id (about 19 MiB of memory per attempt), so unlimited attempts are also a
way to exhaust the server. Registration has the same cost profile and could be
used to fill the database. Users are on Nigerian mobile networks, where
carrier-grade NAT puts thousands of people behind one public IP, so a naive
per-IP limit would lock out innocent users.

## Options considered

### A. In-memory counters (Caffeine or Bucket4j) in each instance
Fastest, no database. But limits are per instance (N instances allow N times
the attempts), and a restart forgets everything.

### B. Counters in PostgreSQL
One row per key, shared by all instances, survives restarts. Costs one small
write per attempt, negligible next to the Argon2 verification it protects.

### C. Redis
The usual choice at scale, but a new service to run and secure for a problem the
database handles at this size.

## Decision

B, as a general-purpose `shared.ratelimit.RateLimiter` (fixed windows, one row per
hashed key). Keys, limits and window are chosen by the caller.

**Login** counts failures against three keys (defaults, all configurable):

| Key | Default | Why |
|---|---|---|
| account + client IP | 5 per 15 min | stops one attacker guessing a password, without letting them lock the victim out of their own devices |
| account, any IP | 20 per 15 min | caps a distributed attack on one account |
| client IP, any account | 100 per 15 min | caps credential stuffing from one source; generous because of shared carrier IPs |

**Registration** counts every attempt per client IP: 20 per hour.

Rules that make it hold up:

1. **Reserve, then refund.** An attempt is counted atomically (`INSERT ... ON CONFLICT
   DO UPDATE ... RETURNING`) before the password is checked; a success hands the
   count back. Under 30 simultaneous guesses, exactly the allowed number reach the
   password check. Counting after the check would let a burst through.
2. **Check before hashing.** A blocked request never reaches Argon2, so blocking
   protects the CPU and memory, not just the account.
3. **Same behaviour for unknown accounts.** The key is the typed identifier, whether or
   not the account exists, so throttling reveals nothing.
4. **Correct password does not bypass a block.** Otherwise the limit would not slow guessing.
5. **Counters are committed independently** (`REQUIRES_NEW`), so a failed login's
   count survives the rollback of the request.
6. **Keys are SHA-256 hashes** of identifier and IP, so the table holds no email
   addresses or IP addresses.
7. **The client address is the TCP peer address.** `X-Forwarded-For` is ignored, since
   any client can forge it. Behind a trusted proxy the deployment must configure
   Spring's forwarded-header handling; documented as a deployment requirement.
8. Blocked requests get **429** with `Retry-After` (seconds) and a problem document.

## Consequences

- Fixed windows allow up to twice the limit across a window boundary. Acceptable for
  these limits.
- An attacker needs more than 20 failures from many IPs to block a victim's account
  everywhere for the rest of the window; 20 guesses per 15 minutes also caps brute
  force at about 1,900 per day per account.
- A user behind a shared IP who mistypes can consume that IP's budget; the pair and
  account keys are tight, the IP key is loose for exactly this reason.
- Refresh tokens are not rate limited: they are 256 random bits, so guessing is
  hopeless, and refresh costs one cheap query.
- A blocked attempt still increments the other keys it touched, so a flood can push an
  IP key higher than the number of attempts that were actually processed.

## Why the alternatives were rejected

A gives different limits depending on how many instances run. C adds infrastructure
before a measured need; the `RateLimiter` interface lets it be swapped later.

## What this does not handle
Slowing responses instead of blocking, CAPTCHA, per-device fingerprinting, alerting
on attack patterns, and rate limiting of endpoints other than login and registration.
