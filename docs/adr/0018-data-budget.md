# ADR 0018: Data budget, data saver and prefetch cap

- Status: accepted
- Date: 2026-10-23

## Context

VISION.md goal 2: "a per-user monthly data budget, a prefetch cap, and 144p and audio-only renditions" so that
users on expensive mobile data "do not burn their data budget". The schema already has the settings columns
(`feed.user_feed_settings`) and a usage table (`feed.usage_events`, partitioned by month). This application never
serves video bytes (object storage does, ADR in CONTRIBUTING), so it cannot meter or block playback itself.

## Decisions

### 1. The server advises; the client obeys; nothing is blocked

The server cannot see the bytes object storage serves, so usage is **reported by the client** and the budget is
**advice** the client follows. The API never refuses playback because a budget is used up. A client that lies about
its usage only misleads its own user's recommendations; nobody else's data is affected. This is stated in the API
and in the docs rather than hidden.

*Rejected: enforcing the cap on the server.* It would need signed, metered download URLs per request and a
central byte counter in the serving path, which is the infrastructure this design deliberately avoids.

### 2. A policy computed from settings and usage

One pure function turns (data saver, monthly cap, bytes used this period, prefetch cap) into the advice the feed
returns with every page:

| State | When | Recommended top rendition | Prefetch allowance |
|---|---|---|---|
| `UNLIMITED` | no cap set, saver off | none (the app chooses) | the prefetch cap |
| `OK` | under 90% of the cap | none | min(prefetch cap, remaining) |
| `LOW` | 90% or more of the cap used | `144p` | min(prefetch cap, remaining) |
| `EXHAUSTED` | cap reached | `audio` | 0 |

Data saver on forces at most `144p` and **no prefetch** whatever the state. The feed returns the state, the remaining
bytes, the recommended rendition and the prefetch allowance in a small `dataPolicy` object.

### 3. The user's real billing cycle, not the calendar month

Mobile data plans renew on the day a person bought them, not on the 1st. Each user sets a billing day (1 to 28; 28 is
the limit so every month has it) and the "month" runs from the most recent billing day to the next, in UTC.
*Rejected: calendar months.* They would show a budget that resets on a day that means nothing to the user.

### 4. Estimating cost before streaming

Each feed item can carry the estimated size of watching it at each rendition (`estimatedBytes`), computed from the
video's duration and each rendition's maximum bitrate (the `video.renditions` table, exposed through the video
module's `RenditionCatalog`). They are upper bounds from the bitrate ladder, **not measurements**; real sizes arrive
with the transcoding ticket (T1.4). The estimates let a user and a client see "this video costs about 0.2 MB as
audio and 1.1 MB at 144p" before spending it.

### 5. Usage reports are batched, deduplicated and bounded

`POST /v1/data/usage` takes up to 100 events, each with a client-generated ID, the video, the rendition, the bytes and
when it happened. A repeated event ID is ignored, so a retried batch cannot double-count, and no `Idempotency-Key` is
needed. Bounds: bytes per event up to 1 GiB, the rendition must exist, the time may not be in the future (5 minutes of
clock skew allowed) or older than 40 days, and a user may send 60 batches per minute (the shared rate limiter).

### 6. Data minimisation

Usage rows older than 400 days are deleted by a scheduled job. They hold a video ID and a byte count per user, which
is personal data under the NDPA, so they are not kept longer than a year-over-year comparison needs.

## Consequences

- The numbers a client sees are only as good as what it reports; the budget is a convenience, not a guarantee.
- `feed.usage_events` is partitioned by month but only a default partition exists. Until monthly partitions are created
  by operations, rows live in the default one; the 400-day purge still keeps it bounded.
- Estimates will be replaced by measured sizes once transcoding exists; the field name stays, the numbers improve.

## What this does not handle
Server-side enforcement, metering of bytes by object storage or a CDN, per-network rules (Wi-Fi versus mobile), detecting
a dishonest client, partition maintenance, and the benchmark of megabytes per ten minutes (T3.2).
