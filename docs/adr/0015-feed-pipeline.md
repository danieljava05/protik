# ADR 0015: The feed pipeline

- Status: accepted
- Date: 2026-10-19

Builds on [ADR 0014](0014-transparent-ranker.md) (how items are scored) and
[ADR 0005](0005-database-structure.md) (the feed owns read models, not other modules' tables).

## Context

`GET /v1/feed` must show each viewer videos ordered by *their own* mode and weights, explain every
item, page without repeats or gaps, and stay cheap in bytes (users pay for data). The feed may not read
the video, social or engagement tables. Weights change the order, so an order cannot be computed once
and stored.

## Decisions

### 1. Rank a bounded pool in memory

Per request: select the newest `pool-size` (default 500) candidates published within `window`
(default 14 days) from the feed's own tables, rank them in Java with the viewer's weights, return a
slice. Nothing is precomputed per viewer.

*Rejected: scoring in SQL.* The explanation would need a second implementation that could drift from
the first. *Rejected: precomputed per-user feeds (fan-out on write).* Storage grows with users times
videos, and a weight change would invalidate every stored feed, which defeats "adjust the weights and
see the result". *Rejected: ranking all candidates.* Cost grows with the catalogue; the ranker takes
under a second for 100,000 but a feed request should not.

Consequence: the ranker can promote only within the pool. A very engaging two-week-old video outside
the newest 500 is never shown. A better candidate generator (per-interest and per-creator pools) is
future work and is listed as a known limit.

### 2. Cursor paging pinned to a moment and to the settings

The first request fixes `asOf`. The cursor carries `asOf`, a fingerprint of the viewer's mode and
effective weights, and the sort key (score, published time, ID) of the last item served. The next
page re-ranks the pool as of the same moment and returns what sorts strictly after that key, using
the same comparison function as the ranker (`RankOrder`), so the two cannot disagree.

- Videos published after `asOf` do not appear mid-session.
- If the viewer changes mode or weights mid-session the cursor is refused (409): the order it
  refers to no longer exists, and silently resuming would repeat or skip videos.
- No offset pagination (CONTRIBUTING rule), and pages cost the same however deep they go within the pool.

**Known limit:** engagement counters are live, not snapshotted per session. If a video's counters change
between pages its score can change, so an item can occasionally be repeated or skipped at a page
boundary. Snapshotting counters per session needs a session store; not built.

### 3. Explanations are optional

Each item can carry its component list (`explain=true`, the default). `explain=false` omits it, roughly
halving the response, for clients on a tight data budget.

### 4. Read models are fed by events through a persistent registry

The video module publishes `VideoPublished` / `VideoRemoved` (its public API). The feed listens with
`@ApplicationModuleListener`: after the publisher's transaction commits, in its own transaction, with the event
held in Spring Modulith's JDBC event publication registry until the listener returns normally. The registry
table is created by Flyway in the `shared` schema (`spring.modulith.events.jdbc.schema=shared`). Handled
events are deleted (the read models can be rebuilt, so no audit trail is kept) and unfinished ones are
re-delivered at startup. Delivery is at-least-once, so every handler is idempotent.

- An event published inside a transaction that rolls back is never delivered.
- A poison event (one whose handler keeps failing) stays in the registry, incomplete, for inspection and
  retry, and does not block other events.

*Rejected: the shared job queue as transport.* It works, but publishers would have to know the
subscriber's job type; events keep the publisher unaware of who listens. *Rejected: synchronous
in-transaction listeners.* A feed failure would then block publishing a video.

### 5. Responses are never cached

Feed pages are personal and time-dependent: `Cache-Control: no-store`.

## Consequences

- Adding a data source for the feed (follows, interests, engagement counters) means one more event type
  and one more idempotent handler.
- The registry table grows only while events are pending or failing; operators should watch for old
  incomplete rows.
- Measured cost of a first page is in DESIGN.md with the hardware.

## What this does not handle
Hiding videos the viewer has already seen (the `seen_videos` table is unused), blocked or muted creators, language
preferences, candidate generation beyond the newest pool, counter snapshots per session, and a
backfill or rebuild command for the read models.
