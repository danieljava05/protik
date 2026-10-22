# ADR 0016: Follows, likes and counters across modules

- Status: accepted
- Date: 2026-10-22

## Context

T5.1 adds follows (`social`), likes (`engagement`) and, later, comments. Modules may not read each
other's tables (ADR 0002, 0005), yet:

- liking or commenting needs to know the video exists and is published (a `video` fact);
- following needs to know the person exists (an `identity` fact);
- the feed needs the follow graph, per-video counters and the viewer's likes (ADR 0015);
- a viral video can receive thousands of likes per second, so a counter updated per like becomes a
  hot row (ADR 0005 decision 3).

## Decisions

### 1. Existence checks go through a small public API of the owning module

`video` exposes `VideoCatalog.find(id)` and `identity` exposes `AccountDirectory.exists(id)`. They are
interfaces in each module's base package, implemented over its own tables. Callers get a yes/no (plus
the creator and published flag), never rows or entities.

*Rejected: each module keeping its own copy of "which videos exist".* That adds an event stream and a
window where a real video looks missing, for a check that is cheap and needs to be right.
*Rejected: foreign keys across schemas.* ADR 0005 forbids them.

### 2. Facts other modules need are announced as events

`social` publishes `UserFollowed` / `UserUnfollowed`; `engagement` publishes `VideoLiked` /
`VideoUnliked` and `VideoStatsChanged`. They are part of those modules' public API. The `feed` keeps
its own read models fed by them (following, the viewer's likes, per-video counters), through the
persistent registry (ADR 0015). Events are published in the same transaction as the change, and only
when the change really happened: re-following someone already followed publishes nothing.

### 3. Counters: a dirty set, recounted from the truth

*Options.* (A) `UPDATE ... SET likes = likes + 1` per like: always consistent, but a hot row.
(B) Add deltas from events: not safe, because delivery is at-least-once and a repeat would count twice.
(C) **Chosen:** the like row is the truth. Each like or unlike also records the video in a tiny
`dirty_counters` set (one row per video, `ON CONFLICT DO NOTHING`). A refresher runs about once per
second, takes a batch of dirty videos (`FOR UPDATE SKIP LOCKED`, so several instances share the work),
recounts each from the truth, stores the counter and publishes `VideoStatsChanged`, all in one
transaction.

Why this holds up: the work per video is bounded to one recount per refresh interval however many
likes arrive; repeating a refresh is harmless (idempotent); a crash re-queues the video because the
dirty row is only removed in the transaction that updates the counter; and a counter that has drifted
for any reason is repaired the next time the video is touched, or by the full reconciliation
(`reconcileAll`, scheduled hourly), which recomputes every counter from the truth. Counters are
eventually consistent (about a second behind) by design.

### 4. The viewer's interests are derived, not asked for

The feed's TOPIC component needs tags the viewer cares about. They are the tags of the videos the
viewer has liked (plus any set explicitly), computed by the feed from its own copies of likes and tags.
Unliking removes that video's influence. No extra user interface is needed to "choose interests".

### 5. Following and liking are PUT/DELETE

`PUT` and `DELETE` on the relationship are idempotent by definition, so they do not need an
`Idempotency-Key`. Both answer 204 whether or not anything changed.

## Consequences

- Likes and follows work only for videos and people that exist; unknown ones are 404, so the endpoints
  cannot be used to probe, and cannot create rows for nonexistent targets.
- Counters shown in the feed can lag a like by about a second; the person who just liked sees their own
  state immediately because the response to their action comes from the truth.
- A like that arrives at the feed before the feed has indexed the video is ignored for interests (rare;
  documented, not repaired).
- Following has no cap yet, and there is no block list.

## What this does not handle
Unfollow-triggered removal of old feed items beyond the next request, notifications, follower and
following lists (only the relationship is queryable), blocking and muting, spam detection on likes.
