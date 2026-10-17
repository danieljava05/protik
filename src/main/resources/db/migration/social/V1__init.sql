-- Module migration. Source design: docs/schema.md, ADR 0005.
-- No foreign keys to other modules' schemas (enforced by SchemaInvariantsTest).

CREATE TABLE social.follows (
  follower_id uuid        NOT NULL,
  followee_id uuid        NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (follower_id, followee_id),
  CHECK (follower_id <> followee_id)
);
-- Serves "who follows X" (the PK serves "whom does X follow")
CREATE INDEX follows_followee_idx ON social.follows (followee_id, created_at DESC);

CREATE TABLE social.blocks (
  blocker_id uuid        NOT NULL,
  blocked_id uuid        NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (blocker_id, blocked_id),
  CHECK (blocker_id <> blocked_id)
);

CREATE TABLE social.comments (
  id         uuid PRIMARY KEY,
  video_id   uuid        NOT NULL,
  author_id  uuid        NOT NULL,
  parent_id  uuid        REFERENCES social.comments(id) ON DELETE CASCADE,
  body       text        NOT NULL CHECK (length(body) BETWEEN 1 AND 2200),
  status     text        NOT NULL DEFAULT 'VISIBLE' CHECK (status IN ('VISIBLE','HIDDEN','REMOVED')),
  created_at timestamptz NOT NULL DEFAULT now()
);
-- Serves top-level comments of a video, cursor-paginated
CREATE INDEX comments_video_idx ON social.comments (video_id, created_at, id) WHERE parent_id IS NULL;
-- Serves replies of a comment (also the FK index for parent_id)
CREATE INDEX comments_parent_idx ON social.comments (parent_id, created_at, id) WHERE parent_id IS NOT NULL;
-- Serves deleting everything an author wrote (account deletion)
CREATE INDEX comments_author_idx ON social.comments (author_id);
