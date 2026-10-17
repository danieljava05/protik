-- Module migration. Source design: docs/schema.md, ADR 0005.
-- No foreign keys to other modules' schemas (enforced by SchemaInvariantsTest).

CREATE TABLE video.languages (
  code          text PRIMARY KEY,            -- BCP 47 / ISO: yo, ig, ha, pcm, en
  name          text    NOT NULL,
  asr_supported boolean NOT NULL DEFAULT false
);

CREATE TABLE video.renditions (
  code             text PRIMARY KEY,         -- '144p','240p','480p','720p','audio'
  height_px        int,                      -- NULL for audio-only
  max_bitrate_kbps int NOT NULL CHECK (max_bitrate_kbps > 0),
  CHECK (height_px IS NULL OR height_px > 0)
);

CREATE TABLE video.videos (
  id              uuid PRIMARY KEY,
  creator_id      uuid        NOT NULL,      -- identity.accounts; no FK (ADR 0005)
  description     text        NOT NULL DEFAULT '' CHECK (length(description) <= 2200),
  spoken_language text REFERENCES video.languages(code),
  duration_ms     int         CHECK (duration_ms > 0),
  status          text        NOT NULL
                    CHECK (status IN ('UPLOADING','PROCESSING','PUBLISHED','FAILED','TAKEN_DOWN')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  published_at    timestamptz,
  -- No DELETED status: deletion really deletes the row (NDPA, VISION.md).
  CHECK (status <> 'PUBLISHED' OR (published_at IS NOT NULL AND duration_ms IS NOT NULL))
);
-- Serves a creator's profile page, cursor-paginated by (published_at, id)
CREATE INDEX videos_creator_pub_idx
  ON video.videos (creator_id, published_at DESC, id DESC) WHERE status = 'PUBLISHED';
-- FK index for spoken_language
CREATE INDEX videos_language_idx ON video.videos (spoken_language);

CREATE TABLE video.upload_sessions (
  id                  uuid PRIMARY KEY,
  creator_id          uuid        NOT NULL,
  video_id            uuid        NOT NULL UNIQUE REFERENCES video.videos(id) ON DELETE CASCADE,
  storage_key         text        NOT NULL UNIQUE,
  multipart_upload_id text        NOT NULL,
  content_type        text        NOT NULL,
  total_bytes         bigint      NOT NULL CHECK (total_bytes > 0),
  status              text        NOT NULL CHECK (status IN ('OPEN','COMPLETED','ABORTED','EXPIRED')),
  created_at          timestamptz NOT NULL DEFAULT now(),
  expires_at          timestamptz NOT NULL,
  CHECK (expires_at > created_at)
);
-- Serves the sweeper that aborts expired multipart uploads (frees storage)
CREATE INDEX upload_sessions_expiry_idx ON video.upload_sessions (expires_at) WHERE status = 'OPEN';

CREATE TABLE video.video_assets (
  id              uuid PRIMARY KEY,
  video_id        uuid        NOT NULL REFERENCES video.videos(id) ON DELETE CASCADE,
  kind            text        NOT NULL
                    CHECK (kind IN ('ORIGINAL','HLS_MASTER','HLS_VARIANT','THUMBNAIL')),
  rendition_code  text        REFERENCES video.renditions(code),
  storage_key     text        NOT NULL UNIQUE,   -- object key or key prefix for HLS variants
  size_bytes      bigint      NOT NULL CHECK (size_bytes >= 0),  -- total across objects
  object_count    int         NOT NULL DEFAULT 1 CHECK (object_count > 0),
  avg_bitrate_kbps int        CHECK (avg_bitrate_kbps > 0),      -- measured, not the ladder maximum
  created_at      timestamptz NOT NULL DEFAULT now(),
  -- Only variants carry a rendition; makes "audio-only" a real, queryable variant.
  CHECK ((kind = 'HLS_VARIANT') = (rendition_code IS NOT NULL)),
  -- A re-run of the transcode job cannot create duplicate assets (idempotent).
  UNIQUE NULLS NOT DISTINCT (video_id, kind, rendition_code)
);
-- FK index for rendition_code; also serves "storage cost per rendition" reports
CREATE INDEX video_assets_rendition_idx ON video.video_assets (rendition_code);

-- Deleting rows must never orphan objects in MinIO/S3. A trigger records every
-- deleted key (including cascade deletes) so a worker deletes the object.
CREATE TABLE video.storage_tombstones (
  storage_key text PRIMARY KEY,
  created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION video.record_tombstone() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO video.storage_tombstones (storage_key) VALUES (OLD.storage_key)
  ON CONFLICT DO NOTHING;
  RETURN OLD;
END $$;
CREATE TRIGGER video_assets_tombstone AFTER DELETE ON video.video_assets
  FOR EACH ROW EXECUTE FUNCTION video.record_tombstone();

CREATE TABLE video.hashtags (
  id  uuid PRIMARY KEY,
  tag citext NOT NULL UNIQUE CHECK (tag ~ '^[^[:space:]#]{1,60}$')
);
CREATE TABLE video.video_hashtags (
  video_id   uuid NOT NULL REFERENCES video.videos(id) ON DELETE CASCADE,
  hashtag_id uuid NOT NULL REFERENCES video.hashtags(id),
  PRIMARY KEY (video_id, hashtag_id)
);
-- Serves "videos for a hashtag"
CREATE INDEX video_hashtags_tag_idx ON video.video_hashtags (hashtag_id, video_id);

-- Captions are first-class (VISION.md goal 3). Segments, not one text blob, so
-- search results can seek to the matching second and WER can be scored per cue.
CREATE TABLE video.captions (
  id               uuid PRIMARY KEY,
  video_id         uuid        NOT NULL REFERENCES video.videos(id) ON DELETE CASCADE,
  language_code    text        NOT NULL REFERENCES video.languages(code),
  source           text        NOT NULL CHECK (source IN ('ASR','CREATOR')),
  status           text        NOT NULL CHECK (status IN ('PENDING','READY','FAILED')),
  model            text,                     -- e.g. model name; needed to compare WER across models
  model_version    text,
  avg_confidence   real        CHECK (avg_confidence BETWEEN 0 AND 1),
  vtt_storage_key  text,                     -- rendered artifact, derived (D5)
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (video_id, language_code, source),
  CHECK (status <> 'READY' OR vtt_storage_key IS NOT NULL),
  CHECK (source <> 'ASR' OR model IS NOT NULL)
);
CREATE INDEX captions_language_idx ON video.captions (language_code);

CREATE TABLE video.caption_segments (
  caption_id  uuid NOT NULL REFERENCES video.captions(id) ON DELETE CASCADE,
  seq         int  NOT NULL CHECK (seq >= 0),
  start_ms    int  NOT NULL CHECK (start_ms >= 0),
  end_ms      int  NOT NULL,
  text        text NOT NULL,
  -- D1: derived search column. Accent-insensitive on purpose: people type
  -- Yoruba/Igbo without tone marks and dots. 'simple' config because Postgres
  -- ships no stemmers for these languages.
  text_tsv    tsvector GENERATED ALWAYS AS
                (to_tsvector('simple', shared.immutable_unaccent(text))) STORED,
  PRIMARY KEY (caption_id, seq),
  CHECK (end_ms > start_ms)
);
-- Serves transcript search (full-text)
CREATE INDEX caption_segments_tsv_idx ON video.caption_segments USING gin (text_tsv);
-- Serves fuzzy / misspelled search (Pidgin spelling varies a lot)
CREATE INDEX caption_segments_trgm_idx
  ON video.caption_segments USING gin (lower(shared.immutable_unaccent(text)) gin_trgm_ops);
