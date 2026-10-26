# ADR 0019: Reporting, moderation and the audit log

- Status: accepted
- Date: 2026-10-23

## Context

A platform with user uploads and comments must let people report content and let trusted staff act on reports, with a
trail of who did what and why (CONTRIBUTING.md: "every state-changing moderation or admin action is audit-logged with actor,
time, and reason"). `moderation.reports` and the append-only `moderation.actions` table already exist (ADR 0005).
Nothing yet distinguishes staff from ordinary users, and moderation must change state owned by other modules (a video's
visibility, a comment, an account) without reaching into their tables.

## Decisions

### 1. Roles are stored by identity and travel in the access token

A new `identity.account_roles` table holds `MODERATOR` and `ADMIN` grants. Access tokens carry them in a `roles` claim and
the security layer maps the claim to authorities; `/v1/moderation/**` requires `ROLE_MODERATOR` (an `ADMIN` is also a
moderator). There is **no API to grant roles yet**. The operator names existing accounts in `protik.bootstrap.moderators` (comma separated
usernames, e.g. `PROTIK_BOOTSTRAP_MODERATORS=ada,chinedu`) and restarts; each grant is logged at WARN, unknown or inactive
usernames are reported and never created, repeats change nothing, and removing a name does not revoke the role (revoking is a
deliberate database operation). A role change takes effect at the user's next login or refresh, because the access token (15 minutes) is the
carrier; removing a role can therefore take up to 15 minutes to bite, an accepted and documented limit.

*Rejected: looking roles up on every request.* A database read per request for a rarely used capability.
*Rejected: a role column on `accounts`.* A person may hold several roles, and grants deserve their own history.

### 2. Reports

`POST /v1/reports` (any signed-in user) files a report about a video, a comment or an account
with a reason code (`CHILD_SAFETY`, `COPYRIGHT`, `HARASSMENT`, `VIOLENCE`, `SPAM`, `OTHER`) and optional details. The target
must exist (checked through the owning module's public API, never its tables) and a user cannot report themselves. One
open report per reporter and target (the schema's unique index) answers 409 on a repeat. A user may file 10 reports per
hour (shared rate limiter). No `Idempotency-Key`: a retry meets the one-open-report rule and answers 409. The reporter's identity is visible to moderators and to no one else.

### 3. The queue

Moderators list open reports through `GET /v1/moderation/reports` with a cursor: `CHILD_SAFETY` first, then oldest first,
so the oldest harm is handled before the newest.

### 4. Actions and their effects

A moderator resolves a report with `POST /v1/moderation/reports/{id}/actions`: `TAKEDOWN` (video), `HIDE_COMMENT`,
`SUSPEND_ACCOUNT`, or `DISMISS`. A **reason is required**. Effects go through small public APIs of the owning modules
(`VideoModeration`, `CommentModeration`, `AccountAdministration`), each idempotent. Taking a video down publishes
`VideoRemoved`, so the feed drops it; hiding a comment changes the comment count; suspending an account revokes its sessions.
Resolving a report also settles every other open report about the same target. The effect, the new report status and the audit row are written **in one transaction**: the audit log cannot miss an action
that happened, nor record one that did not.

### 5. The audit log

`moderation.actions` is append-only (database triggers refuse update, delete and truncate). Each row has the acting
moderator, the action, the target, the mandatory reason, the report and the time. Moderators can read the history of a target
through `GET /v1/moderation/audit`. The log keeps only pseudonymous IDs, so it can outlive an account deletion (ADR 0020).

### 6. Child safety

`CHILD_SAFETY` reports are first in the queue and a takedown is immediate, but **a technical queue is not a legal procedure**.
What must be reported to which authority, and how evidence is preserved, is a legal question to settle before public
launch; this ADR does not answer it.

## Consequences

- Moderation depends on `video`, `social` and `identity` public APIs; none depends on moderation.
- Staff actions are only as trustworthy as the staff accounts: role grants are manual and unaudited until an admin API exists.
- There is no restore action and no appeal workflow yet: a takedown is undone only by changing the database.

## What this does not handle
Restoring a taken-down video, appeals, a legal-hold and evidence procedure, automated detection, moderator tooling beyond the API, granting roles through the
API, two-person approval for severe actions, and reporter protection beyond hiding their identity from other users.
