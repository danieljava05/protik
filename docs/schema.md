# Logical schema

Status: **migrated and tested** (ticket T0.2). The source of truth for
columns, constraints and indexes is the per-module Flyway migrations in
`src/main/resources/db/migration/<module>/`, checked against a real
PostgreSQL by `SchemaTests` (see [ADR 0007](adr/0007-per-module-migrations.md)).
Structural decisions are in
[ADR 0005](adr/0005-database-structure.md). This page is the map.

Conventions: one PostgreSQL schema per module, UUID primary keys, and a
column ending in `_id` that points into another module is a plain value
with **no foreign key**.

## Tables by module

| Schema | Tables |
|---|---|
| `identity` | `accounts`, `login_identifiers`, `password_credentials`, `profiles`, `session_families`, `refresh_tokens`, `deletion_requests`, `deletion_acks` |
| `video` | `languages`, `renditions`, `videos`, `upload_sessions`, `video_assets`, `storage_tombstones`, `hashtags`, `video_hashtags`, `captions`, `caption_segments` |
| `feed` | `ranking_components`, `ranking_modes`, `ranking_mode_weights`, `user_feed_settings`, `user_ranking_weights`, `usage_events`, `seen_videos`, `candidates`, `candidate_stats` |
| `social` | `follows`, `blocks`, `comments` |
| `engagement` | `video_likes`, `view_events`, `video_counters` |
| `moderation` | `reports`, `actions` (append-only audit log) |
| `shared` | `jobs`, `idempotency_keys` |

## How the schema serves the vision

- **Transparent ranking:** `ranking_mode_weights` publishes the weights of
  every mode, so even `DEFAULT` is inspectable; `user_ranking_weights`
  holds per-user overrides. Components are rows, not strings.
- **Data cost:** `renditions` includes `144p` and `audio`;
  `video_assets` records measured `size_bytes` and `avg_bitrate_kbps` per
  rendition, which is where storage cost is read from. `user_feed_settings`
  holds the monthly cap and prefetch cap; `usage_events` holds
  client-reported bytes.
- **African languages:** captions are first-class with segments, model
  name and version, and confidence. Search is accent-insensitive so
  "oro" finds "ọ̀rọ̀", and trigram search tolerates varied Pidgin spelling.

## Deliberate denormalizations

| ID | What | Why | How it stays consistent |
|---|---|---|---|
| D1 | `caption_segments.text_tsv` (generated) | transcript search without scanning text | generated column; cannot drift |
| D2 | `feed.candidates` | keeps video lookups off the feed hot path | updated from video events; rebuildable from the `video` API |
| D3 | `feed.candidate_stats` | ranking inputs without cross-module reads | updated from engagement events; rebuildable |
| D4 | `engagement.video_counters` | O(1) counts, hot rows isolated | async batched updates; scheduled recompute from `video_likes` and `view_events`; load test compares both |
| D5 | `captions.vtt_storage_key` (rendered file) | the player needs a VTT file | regenerated from `caption_segments`; segments are the truth |

## Changes from the first contributor draft, and why

Kept from it: lookup tables for evolving sets, `CHECK` constraints,
partial indexes, `UNIQUE NULLS NOT DISTINCT` for idempotent transcodes,
`citext` hashtags, a generated search column, and counters on their own
row to avoid hot-row contention.

Changed:
1. **Cross-module foreign keys removed** (`creator_id` to `users`, and
   so on) and tables split into per-module schemas; the draft would have
   broken the module rule. A test now forbids them.
2. **Public IDs:** `bigint` identity keys violate the "no sequential IDs
   in URLs" rule unless a second public ID is added. Keys are UUIDs
   (ADR 0005 decision 2); switching back would need a `public_id` column.
3. **No `DELETED` status:** deletion must really delete. A trigger
   records every removed storage key so object storage is cleaned and
   nothing is orphaned (cascades included).
4. **Captions:** one text blob and one row per language could not
   store both an ASR and a creator caption, could not link search hits
   to timestamps, and recorded no model. Now segments plus model,
   version and confidence.
5. **Search:** `to_tsvector('simple', ...)` would not match Yoruba text
   typed without tone marks. Accent-insensitive via an immutable
   `unaccent` wrapper.
6. **`ranking_mode` CHECK list** replaced by a lookup table with
   published weights, consistent with the other lookups.
7. **Added:** identity, social, engagement, moderation, job queue,
   idempotency keys, deletion tracking, prefetch cap, usage ledger,
   one-open-report-per-target, append-only audit log, and constraints
   such as "published implies `published_at` and duration".

## Open questions
- Phone-number login needs an OTP provider and a cost model; deferred
  to the identity ticket.
- Comment counts: mirror from `social` events into `video_counters`
  or have `social` expose a count API? Decide in T5.1 with a measurement.
- Unicode normalisation (NFC) of Yoruba text before indexing is not
  handled yet; decide with ADR 0004's protocol.
- `unaccent` also strips the dot below, which merges distinct Yoruba
  words (for example `ọ` and `o`) in search. That is intended for
  recall; ranking exact matches higher is a later refinement.
