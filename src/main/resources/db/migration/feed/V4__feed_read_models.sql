-- T2.3: the feed's own copies of what it needs from other modules (ADR 0005 decision 4).
-- Fed by events, rebuildable, and never joined to another module's tables.

-- Hashtags of a candidate video (D2 in docs/schema.md), for the TOPIC component.
CREATE TABLE feed.candidate_tags (
  video_id uuid NOT NULL REFERENCES feed.candidates(video_id) ON DELETE CASCADE,
  tag      text NOT NULL CHECK (tag ~ '^[^[:space:]#]{1,60}$'),
  PRIMARY KEY (video_id, tag)
);

-- Who follows whom, as the feed needs it (fed by social events): FOLLOWING component and the
-- "only creators you follow" mode. PK (user_id, creator_id) serves "whom does this viewer follow".
CREATE TABLE feed.following (
  user_id    uuid NOT NULL,
  creator_id uuid NOT NULL,
  PRIMARY KEY (user_id, creator_id),
  CHECK (user_id <> creator_id)
);
-- Serves removing a creator's followers' rows when the creator's account is deleted.
CREATE INDEX following_creator_idx ON feed.following (creator_id);

-- Tags the viewer engages with (fed by engagement events): TOPIC component.
CREATE TABLE feed.user_interests (
  user_id uuid NOT NULL,
  tag     text NOT NULL CHECK (tag ~ '^[^[:space:]#]{1,60}$'),
  PRIMARY KEY (user_id, tag)
);
