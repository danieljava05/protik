# ADR 0020: Account deletion that really deletes

- Status: accepted
- Date: 2026-10-23

## Context

The Nigeria Data Protection Act (NDPA) gives people the right to have their personal data erased, and VISION.md /
CONTRIBUTING.md say "account deletion must actually delete". A person's data is spread over every module, each in its own
schema with no cross-schema foreign keys (ADR 0005), so a single `DELETE ... CASCADE` cannot reach it. Some records
(moderation audit, reports about other people's content) have a legitimate reason to survive in a reduced form.

## Decisions

### 1. A coordinated, acknowledged deletion

`POST /v1/me/deletion` (the caller re-enters their password; a body on `DELETE` is dropped by many clients and proxies, so this is a POST) starts a deletion. Wrong passwords are limited to 5 an hour per account:

1. `identity` marks the account `DELETION_PENDING` (login and refresh stop working at once), revokes every session,
   records a `deletion_request` and publishes `UserDeletionRequested(userId, requestId)`.
2. Each module that holds personal data listens, deletes what it holds in its own transaction, and acknowledges through an
   `identity` API. Acknowledging is idempotent.
3. When every required module has acknowledged, `identity` deletes the account, its credentials, identifiers, profile and
   sessions, and completes the request.

If a module has not acknowledged, the account stays `DELETION_PENDING` and the request stays visible; a periodic job
(every 10 minutes by default) re-publishes the request for any older than 10 minutes. Acknowledgements are counted under a row lock, so modules finishing at the same moment cannot miss each other. Nothing is silently dropped.

*Rejected: one big transaction across modules.* It couples every module to deletion and cannot cross schemas by design.
*Rejected: soft delete (a flag).* It does not erase, which the law and the project rules require.

### 2. What each module deletes

| Module | Deleted | Kept, and why |
|---|---|---|
| `video` | the user's videos (they leave the feed via `VideoRemoved`) and upload sessions. **Stored files, assets and captions do not exist yet; the uploads ticket (T1.3) must delete them here, and the completeness test must then cover them** | nothing |
| `social` | comments the user wrote (with replies under them), follows in both directions, blocks | nothing |
| `engagement` | the user's likes and view events; counters of affected videos are recounted | nothing |
| `feed` | settings and weight overrides, usage reports, seen list, interests, derived likes, follow graph copies | nothing |
| `moderation` | the free-text details of reports the user filed, and their reporter ID is replaced by a fresh random ID per report, so reports cannot be linked to each other either | the report itself (it concerns other people's content), and the audit log (pseudonymous IDs, no personal data) |
| `shared` | the user's idempotency keys, removed by identity when the last module has acknowledged | rate-limit counters (hashed keys, expire on their own) |

### 3. Content the user liked, followed or commented on stays, minus the user

Other people's videos and the numbers on them are not erased: only the deleted person's contributions go, and counters are
recounted from the truth afterwards.

### 4. Deletion is tested for completeness

`AccountDeletionIntegrationTests` seeds a row in every column that can hold a user ID, deletes the account through the API,
and asserts that none is left (the one deliberate exception is the completed deletion request itself, which keeps only the
now-meaningless ID). A second test fails if a table with a user-ID-named column (`user_id`, `account_id`, `creator_id`,
`author_id`, `follower_id`, `followee_id`, `blocker_id`, `blocked_id`, `reporter_id`) is added without being listed, and a
third fails if the seed leaves a listed column empty, so the check cannot pass vacuously. It catches a *forgotten table*; a
user ID stored in a differently named column is invisible to it.

### 5. What deletion cannot reach

An access token already issued stays valid until it expires (15 minutes), because access tokens are checked without a
database lookup (ADR 0011). In that window the person could still write something new into a module that has already
finished deleting. The sessions are revoked at once, so they cannot refresh, but that window is a known gap.

Backups and logs outside the database, caches in clients, and anything a third party already received. The privacy notice
(pre-launch, roadmap) must say so, and a retention period for backups must be defined.

## Consequences

- Deletion is asynchronous and may take moments; the account is unusable immediately.
- A module added later must join the deletion protocol (the completeness test enforces this for new tables).
- Report details are cleared, which may weaken a pending moderation case; that is the privacy-preserving choice.

## What this does not handle
Export of a user's data (the NDPA right of access), a cooling-off period during which deletion can be cancelled, deletion of
data in backups, and deletion requests made on behalf of a minor.
