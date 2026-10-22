# ADR 0017: Comments

- Status: accepted
- Date: 2026-10-22

Completes T5.1 on top of [ADR 0016](0016-social-and-engagement.md).

## Context

Comments are the first user-written text on the platform: they need size and content limits, threading,
cursor paging, abuse limits, and a counter that the feed can use. `social.comments` already exists (ADR 0005)
with a self-referencing `parent_id` and a status column reserved for moderation.

## Decisions

### 1. Threading: one level

A comment is top-level or a reply to a top-level comment. Replying to a reply is refused (400) rather than
silently flattened. Deep threads cost recursive queries and confuse a small screen; one level is enough for
short videos and can be extended later without changing stored data.

### 2. Content rules

1 to 2,200 characters of plain text. NUL and other control characters (except newline and tab) are refused;
leading and trailing blanks are trimmed; a comment that is blank after trimming is refused. The server stores
text as given and never interprets it as markup: escaping is the client's job when displaying (the API says so).

### 3. Writing is a non-idempotent POST, so it takes an `Idempotency-Key`

`POST /v1/videos/{id}/comments` uses `IdempotencyService` scoped to the user (ADR 0009): a retry after a dropped
connection returns the original comment instead of posting twice. Deleting is a `DELETE` and is idempotent by nature.

### 4. Reading: newest first, keyset cursor

`GET /v1/videos/{id}/comments` returns top-level comments newest first;
`GET /v1/comments/{id}/replies` returns replies oldest first (a conversation reads forward). Both page by
`(created_at, id)` with an opaque cursor, never an offset, served by the existing indexes. Each top-level comment
carries its reply count. Hidden or removed comments (moderation, T5.2) never appear and are not counted.

### 5. Deleting really deletes

Only the author can delete (403 for anyone else; moderator removal arrives with T5.2). The row is deleted, and its
replies go with it (`ON DELETE CASCADE` on `parent_id`). No soft-delete flag: the NDPA expects deletion to delete.

### 6. The counter: social announces, engagement recounts

Adding or deleting a comment publishes `CommentCountChanged(videoId)` (only when something really changed).
`engagement` reacts by marking the video dirty, exactly as for likes; its refresher recounts comments through
the public `CommentCounts` interface of `social` and likes from its own rows, then announces `VideoStatsChanged`.
So counters stay recounted-from-truth (ADR 0016), `engagement` depends on `social`'s public API, and `social`
depends on nothing in `engagement`.

### 7. Abuse limit

A user may post at most 20 comments per minute, enforced with the shared `RateLimiter` (ADR 0013) and answered
with 429 and `Retry-After`. Reading is not limited here.

### 8. Existence checks

Commenting and reading require a published video (`VideoCatalog`); anything else is a 404, so the endpoints
cannot be used to probe for unpublished videos.

## Consequences

- Comment counts lag by about a second, like like counts.
- A comment on a video later taken down remains in the table until moderation or deletion rules remove it; it is
  unreachable through the API because the video is no longer published.
- Replying counts toward the video's comment total; deleting a top-level comment lowers the total by the size of
  its thread.

## What this does not handle
Editing comments, reactions to comments, mentions, notifications, spam and profanity detection, blocking users
from commenting, and moderator hide/remove (T5.2).
