-- Module migration. Source design: docs/schema.md, ADR 0005.
-- No foreign keys to other modules' schemas (enforced by SchemaInvariantsTest).

CREATE TABLE feed.ranking_components (
  code        text PRIMARY KEY,     -- RECENCY, ENGAGEMENT, FOLLOWING, TOPIC
  description text NOT NULL
);
CREATE TABLE feed.ranking_modes (
  code        text PRIMARY KEY,     -- DEFAULT, CHRONOLOGICAL, FOLLOWING_ONLY, TOPIC_FOCUSED
  description text NOT NULL
);
-- Each mode is defined by published weights, so "DEFAULT" is not a black box.
CREATE TABLE feed.ranking_mode_weights (
  mode_code      text NOT NULL REFERENCES feed.ranking_modes(code),
  component_code text NOT NULL REFERENCES feed.ranking_components(code),
  weight         numeric(4,3) NOT NULL CHECK (weight BETWEEN 0 AND 1),
  PRIMARY KEY (mode_code, component_code)
);
CREATE INDEX ranking_mode_weights_component_idx ON feed.ranking_mode_weights (component_code);

CREATE TABLE feed.user_feed_settings (
  user_id             uuid PRIMARY KEY,
  ranking_mode        text    NOT NULL DEFAULT 'DEFAULT' REFERENCES feed.ranking_modes(code),
  data_saver          boolean NOT NULL DEFAULT false,
  monthly_data_cap_mb int     CHECK (monthly_data_cap_mb > 0),   -- NULL = no cap
  prefetch_cap_mb     int     NOT NULL DEFAULT 5 CHECK (prefetch_cap_mb >= 0)
);
CREATE INDEX user_feed_settings_mode_idx ON feed.user_feed_settings (ranking_mode);

-- Per-user overrides on top of the mode's weights.
CREATE TABLE feed.user_ranking_weights (
  user_id        uuid NOT NULL REFERENCES feed.user_feed_settings(user_id) ON DELETE CASCADE,
  component_code text NOT NULL REFERENCES feed.ranking_components(code),
  weight         numeric(4,3) NOT NULL CHECK (weight BETWEEN 0 AND 1),
  PRIMARY KEY (user_id, component_code)
);
CREATE INDEX user_ranking_weights_component_idx ON feed.user_ranking_weights (component_code);

-- Client-reported bytes (the server cannot see CDN-served bytes). Partitioned
-- monthly; old months are dropped, not deleted row by row. The default
-- partition catches rows until an operator/job creates the month's partition.
CREATE TABLE feed.usage_events (
  id             uuid        NOT NULL,
  user_id        uuid        NOT NULL,
  video_id       uuid        NOT NULL,
  rendition_code text        NOT NULL,    -- video.renditions.code; no FK (cross-module)
  bytes          bigint      NOT NULL CHECK (bytes >= 0),
  occurred_at    timestamptz NOT NULL,
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE TABLE feed.usage_events_default PARTITION OF feed.usage_events DEFAULT;
-- Serves "bytes used this month by user" for the budget check
CREATE INDEX usage_events_user_idx ON feed.usage_events (user_id, occurred_at);

CREATE TABLE feed.seen_videos (
  user_id  uuid        NOT NULL,
  video_id uuid        NOT NULL,
  seen_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, video_id)
);
-- Serves trimming old rows
CREATE INDEX seen_videos_age_idx ON feed.seen_videos (seen_at);

-- D2: read model fed by video events; rebuildable.
CREATE TABLE feed.candidates (
  video_id     uuid PRIMARY KEY,
  creator_id   uuid        NOT NULL,
  language     text,
  published_at timestamptz NOT NULL,
  duration_ms  int         NOT NULL CHECK (duration_ms > 0)
);
-- Serves the chronological and default feed, cursor on (published_at, video_id)
CREATE INDEX candidates_pub_idx ON feed.candidates (published_at DESC, video_id DESC);
-- Serves FOLLOWING_ONLY mode
CREATE INDEX candidates_creator_idx ON feed.candidates (creator_id, published_at DESC);

-- D3: read model fed by engagement events; rebuildable.
CREATE TABLE feed.candidate_stats (
  video_id uuid PRIMARY KEY REFERENCES feed.candidates(video_id) ON DELETE CASCADE,
  likes    bigint      NOT NULL DEFAULT 0 CHECK (likes >= 0),
  comments bigint      NOT NULL DEFAULT 0 CHECK (comments >= 0),
  views    bigint      NOT NULL DEFAULT 0 CHECK (views >= 0),
  as_of    timestamptz NOT NULL DEFAULT now()
);
