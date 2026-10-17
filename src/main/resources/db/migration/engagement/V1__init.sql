-- Module migration. Source design: docs/schema.md, ADR 0005.
-- No foreign keys to other modules' schemas (enforced by SchemaInvariantsTest).

-- PK makes a double-like impossible by construction.
CREATE TABLE engagement.video_likes (
  user_id    uuid        NOT NULL,
  video_id   uuid        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, video_id)
);
-- Serves recounting likes per video (reconciliation of D4)
CREATE INDEX video_likes_video_idx ON engagement.video_likes (video_id);

CREATE TABLE engagement.view_events (
  id          uuid        NOT NULL,
  video_id    uuid        NOT NULL,
  user_id     uuid,                       -- NULL for anonymous views
  watched_ms  int         NOT NULL CHECK (watched_ms >= 0),
  completed   boolean     NOT NULL DEFAULT false,
  occurred_at timestamptz NOT NULL,
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE TABLE engagement.view_events_default PARTITION OF engagement.view_events DEFAULT;
-- Serves recounting views per video
CREATE INDEX view_events_video_idx ON engagement.view_events (video_id, occurred_at);

-- D4: cached counters, own row to isolate write contention from video rows.
CREATE TABLE engagement.video_counters (
  video_id   uuid PRIMARY KEY,
  likes      bigint      NOT NULL DEFAULT 0 CHECK (likes >= 0),
  comments   bigint      NOT NULL DEFAULT 0 CHECK (comments >= 0),
  views      bigint      NOT NULL DEFAULT 0 CHECK (views >= 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);
