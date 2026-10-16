-- Validation of schema.sql. Run after schema.sql in the same database.
-- Every "expect_fail" must raise the stated SQLSTATE; any surprise aborts
-- the run with a TEST FAILED message. See README.md.
\set ON_ERROR_STOP on
\pset pager off

CREATE FUNCTION pg_temp.expect_fail(stmt text, expected text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE stmt;
  RAISE EXCEPTION 'TEST FAILED (no error raised): %', stmt USING ERRCODE = 'XX999';
EXCEPTION WHEN OTHERS THEN
  IF SQLSTATE = 'XX999' THEN RAISE; END IF;
  IF SQLSTATE <> expected THEN
    RAISE EXCEPTION 'TEST FAILED (got %, expected %): %', SQLSTATE, expected, stmt USING ERRCODE = 'XX999';
  END IF;
END $$;

CREATE FUNCTION pg_temp.expect_true(label text, cond boolean) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
  IF cond IS NOT TRUE THEN
    RAISE EXCEPTION 'TEST FAILED: %', label USING ERRCODE = 'XX999';
  END IF;
END $$;

BEGIN;

-- Fixtures
INSERT INTO identity.accounts (id, username) VALUES
  ('00000000-0000-0000-0000-000000000001', 'Ada_01'),
  ('00000000-0000-0000-0000-000000000002', 'tunde');
INSERT INTO video.videos (id, creator_id, status, published_at, duration_ms) VALUES
  ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001',
   'PUBLISHED', now(), 15000);

-- ---- identity ----
SELECT pg_temp.expect_fail($$INSERT INTO identity.accounts (id, username) VALUES (gen_random_uuid(), 'ADA_01')$$, '23505'); -- case-insensitive unique
SELECT pg_temp.expect_fail($$INSERT INTO identity.accounts (id, username) VALUES (gen_random_uuid(), 'a b')$$, '23514');   -- bad username
SELECT pg_temp.expect_fail($$INSERT INTO identity.refresh_tokens (id, family_id, token_hash, expires_at)
  VALUES (gen_random_uuid(), gen_random_uuid(), '\x01', now() + interval '1 day')$$, '23503');                              -- orphan token
SELECT pg_temp.expect_fail($$INSERT INTO identity.session_families (id, account_id, revoked_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', now())$$, '23514');                                    -- revoked without reason

-- ---- video ----
SELECT pg_temp.expect_fail($$INSERT INTO video.videos (id, creator_id, status)
  VALUES (gen_random_uuid(), gen_random_uuid(), 'PUBLISHED')$$, '23514');        -- published needs published_at + duration
SELECT pg_temp.expect_fail($$INSERT INTO video.videos (id, creator_id, status)
  VALUES (gen_random_uuid(), gen_random_uuid(), 'DELETED')$$, '23514');          -- no soft-delete state
SELECT pg_temp.expect_fail($$INSERT INTO video.videos (id, creator_id, status, spoken_language)
  VALUES (gen_random_uuid(), gen_random_uuid(), 'UPLOADING', 'zz')$$, '23503');  -- unknown language

INSERT INTO video.video_assets (id, video_id, kind, rendition_code, storage_key, size_bytes) VALUES
  (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'ORIGINAL', NULL, 'k/orig', 1000),
  (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'HLS_VARIANT', '144p', 'k/144', 100),
  (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'HLS_VARIANT', 'audio', 'k/audio', 40);

-- transcode re-run must not duplicate (NULL rendition included: NULLS NOT DISTINCT)
SELECT pg_temp.expect_fail($$INSERT INTO video.video_assets (id, video_id, kind, rendition_code, storage_key, size_bytes)
  VALUES (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'ORIGINAL', NULL, 'k/orig2', 1)$$, '23505');
SELECT pg_temp.expect_fail($$INSERT INTO video.video_assets (id, video_id, kind, rendition_code, storage_key, size_bytes)
  VALUES (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'HLS_VARIANT', '144p', 'k/144b', 1)$$, '23505');
SELECT pg_temp.expect_fail($$INSERT INTO video.video_assets (id, video_id, kind, rendition_code, storage_key, size_bytes)
  VALUES (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'HLS_VARIANT', NULL, 'k/x', 1)$$, '23514');
SELECT pg_temp.expect_fail($$INSERT INTO video.video_assets (id, video_id, kind, rendition_code, storage_key, size_bytes)
  VALUES (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'THUMBNAIL', '144p', 'k/t', 1)$$, '23514');

-- Deleting a video must tombstone every object key (no orphaned storage).
DELETE FROM video.videos WHERE id = '10000000-0000-0000-0000-000000000001';
SELECT pg_temp.expect_true('cascade delete tombstoned all 3 objects',
  (SELECT count(*) FROM video.storage_tombstones) = 3);

-- captions
INSERT INTO video.videos (id, creator_id, status) VALUES
  ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'PROCESSING');
INSERT INTO video.captions (id, video_id, language_code, source, status, model) VALUES
  ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'yo', 'ASR', 'PENDING', 'm');
SELECT pg_temp.expect_fail($$INSERT INTO video.captions (id, video_id, language_code, source, status)
  VALUES (gen_random_uuid(), '10000000-0000-0000-0000-000000000002', 'ig', 'ASR', 'PENDING')$$, '23514'); -- ASR needs model
SELECT pg_temp.expect_fail($$UPDATE video.captions SET status = 'READY' WHERE language_code = 'yo'$$, '23514'); -- READY needs vtt
SELECT pg_temp.expect_fail($$INSERT INTO video.captions (id, video_id, language_code, source, status, model)
  VALUES (gen_random_uuid(), '10000000-0000-0000-0000-000000000002', 'yo', 'ASR', 'PENDING', 'm2')$$, '23505');
SELECT pg_temp.expect_fail($$INSERT INTO video.caption_segments (caption_id, seq, start_ms, end_ms, text)
  VALUES ('20000000-0000-0000-0000-000000000001', 0, 500, 500, 'x')$$, '23514'); -- end must be after start

-- Transcript search: Yoruba with tone marks and dots below must be found by
-- a user typing plain ASCII. U&'..' escapes keep the file encoding-safe.
-- "Ọ̀rọ̀ dára" = O-dot-below + grave, r, o-dot-below + grave
INSERT INTO video.caption_segments (caption_id, seq, start_ms, end_ms, text) VALUES
  ('20000000-0000-0000-0000-000000000001', 0, 0, 2000, U&'\1ECC\0300r\1ECD\0300 d\00E1ra'),
  ('20000000-0000-0000-0000-000000000001', 1, 2000, 4000, 'how far na');
SELECT pg_temp.expect_true('plain "oro" finds accented Yoruba segment',
  EXISTS (SELECT 1 FROM video.caption_segments
          WHERE text_tsv @@ plainto_tsquery('simple', shared.immutable_unaccent('oro'))));
SELECT pg_temp.expect_true('plain "dara" finds "dára"',
  EXISTS (SELECT 1 FROM video.caption_segments
          WHERE text_tsv @@ plainto_tsquery('simple', 'dara')));
SELECT pg_temp.expect_true('trigram fuzzy finds "how far na" from "how far"',
  EXISTS (SELECT 1 FROM video.caption_segments
          WHERE lower(shared.immutable_unaccent(text)) % 'how far'));

-- ---- feed ----
SELECT pg_temp.expect_fail($$INSERT INTO feed.user_feed_settings (user_id, ranking_mode)
  VALUES (gen_random_uuid(), 'MADE_UP')$$, '23503');
INSERT INTO feed.user_feed_settings (user_id) VALUES ('00000000-0000-0000-0000-000000000001');
SELECT pg_temp.expect_fail($$INSERT INTO feed.user_ranking_weights (user_id, component_code, weight)
  VALUES ('00000000-0000-0000-0000-000000000001', 'RECENCY', 1.5)$$, '23514');
SELECT pg_temp.expect_fail($$UPDATE feed.user_feed_settings SET monthly_data_cap_mb = 0$$, '23514');
INSERT INTO feed.usage_events (id, user_id, video_id, rendition_code, bytes, occurred_at)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', gen_random_uuid(), '144p', 123456, now());
SELECT pg_temp.expect_true('usage event landed in a partition',
  (SELECT count(*) FROM feed.usage_events_default) = 1);
-- every mode defines every component (a mode must not hide a weight)
SELECT pg_temp.expect_true('each mode has all components',
  NOT EXISTS (SELECT 1 FROM feed.ranking_modes m CROSS JOIN feed.ranking_components c
              WHERE NOT EXISTS (SELECT 1 FROM feed.ranking_mode_weights w
                                WHERE w.mode_code = m.code AND w.component_code = c.code)));

-- ---- social / engagement ----
SELECT pg_temp.expect_fail($$INSERT INTO social.follows (follower_id, followee_id)
  VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001')$$, '23514');
INSERT INTO engagement.video_likes (user_id, video_id)
  VALUES ('00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002');
SELECT pg_temp.expect_fail($$INSERT INTO engagement.video_likes (user_id, video_id)
  VALUES ('00000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002')$$, '23505');
SELECT pg_temp.expect_fail($$INSERT INTO engagement.video_counters (video_id, likes)
  VALUES (gen_random_uuid(), -1)$$, '23514');
SELECT pg_temp.expect_fail($$INSERT INTO social.comments (id, video_id, author_id, body)
  VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '')$$, '23514');

-- ---- moderation ----
INSERT INTO moderation.reports (id, reporter_id, target_type, target_id, reason_code)
  VALUES ('30000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
          'VIDEO', '10000000-0000-0000-0000-000000000002', 'SPAM');
SELECT pg_temp.expect_fail($$INSERT INTO moderation.reports (id, reporter_id, target_type, target_id, reason_code)
  VALUES (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'VIDEO',
          '10000000-0000-0000-0000-000000000002', 'SPAM')$$, '23505');           -- duplicate open report
INSERT INTO moderation.actions (id, report_id, actor_id, action_type, target_type, target_id, reason)
  VALUES ('40000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
          gen_random_uuid(), 'TAKEDOWN', 'VIDEO', '10000000-0000-0000-0000-000000000002', 'spam');
SELECT pg_temp.expect_fail($$INSERT INTO moderation.actions (id, actor_id, action_type, target_type, target_id, reason)
  VALUES (gen_random_uuid(), gen_random_uuid(), 'TAKEDOWN', 'VIDEO', gen_random_uuid(), '   ')$$, '23514'); -- reason required
SELECT pg_temp.expect_fail($$UPDATE moderation.actions SET reason = 'edited'$$, 'P0001');   -- append-only
SELECT pg_temp.expect_fail($$DELETE FROM moderation.actions$$, 'P0001');
SELECT pg_temp.expect_fail($$TRUNCATE moderation.actions$$, 'P0001');

-- ---- shared ----
SELECT pg_temp.expect_fail($$INSERT INTO shared.jobs (id, type, status) VALUES (gen_random_uuid(), 't', 'RUNNING')$$, '23514'); -- running needs a lease
INSERT INTO shared.idempotency_keys (user_id, key, endpoint, request_hash, expires_at)
  VALUES ('00000000-0000-0000-0000-000000000001', 'k1', 'POST /v1/videos', '\xaa', now() + interval '1 day');
SELECT pg_temp.expect_fail($$INSERT INTO shared.idempotency_keys (user_id, key, endpoint, request_hash, expires_at)
  VALUES ('00000000-0000-0000-0000-000000000001', 'k1', 'POST /v1/videos', '\xbb', now() + interval '1 day')$$, '23505');

-- job claim query must run, and must be able to use the partial index
INSERT INTO shared.jobs (id, type) VALUES (gen_random_uuid(), 'TRANSCODE');
WITH c AS (SELECT id FROM shared.jobs WHERE status = 'QUEUED' AND run_at <= now()
           ORDER BY run_at FOR UPDATE SKIP LOCKED LIMIT 1)
UPDATE shared.jobs j SET status = 'RUNNING', lease_expires_at = now() + interval '5 min', attempts = attempts + 1
FROM c WHERE j.id = c.id;
SELECT pg_temp.expect_true('claim moved exactly one job to RUNNING with a lease',
  (SELECT count(*) FROM shared.jobs WHERE status = 'RUNNING' AND attempts = 1) = 1);

-- ---- structural invariants over the whole catalog ----
-- 1. No foreign key crosses a module (schema) boundary.
SELECT pg_temp.expect_true('no cross-schema foreign keys',
  NOT EXISTS (SELECT 1 FROM pg_constraint c
              JOIN pg_class a ON a.oid = c.conrelid
              JOIN pg_class b ON b.oid = c.confrelid
              WHERE c.contype = 'f' AND a.relnamespace <> b.relnamespace));

-- 2. Every foreign key column has a supporting index (fast cascades, joins).
CREATE TEMP TABLE unindexed_fks AS
SELECT c.conrelid::regclass AS tbl, c.conname
FROM pg_constraint c
WHERE c.contype = 'f'
  AND NOT EXISTS (
    SELECT 1 FROM pg_index i
    WHERE i.indrelid = c.conrelid
      AND (i.indkey::int2[])[0] = c.conkey[1]);
SELECT pg_temp.expect_true('every FK has a leading-column index',
  NOT EXISTS (SELECT 1 FROM unindexed_fks));

-- 3. Every table has a primary key.
SELECT pg_temp.expect_true('every table has a primary key',
  NOT EXISTS (SELECT 1 FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace
              WHERE t.relkind IN ('r','p') AND n.nspname IN
                ('identity','video','feed','social','engagement','moderation','shared')
                AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = t.oid AND k.contype = 'p')));

ROLLBACK;
\echo ALL SCHEMA TESTS PASSED
