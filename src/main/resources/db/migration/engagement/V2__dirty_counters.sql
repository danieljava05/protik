-- ADR 0016 decision 3: counters are recounted from the truth, not incremented per like.
-- A like or unlike records its video here (one row per video, however many likes arrive);
-- a refresher takes the rows, recounts each video from video_likes, and stores the counter.
-- The row is removed in the same transaction that updates the counter, so a crash re-queues it.

CREATE TABLE engagement.dirty_counters (
  video_id  uuid        PRIMARY KEY,
  marked_at timestamptz NOT NULL DEFAULT now()
);

-- Serves "oldest dirty videos first" when the refresher claims a batch.
CREATE INDEX dirty_counters_marked_idx ON engagement.dirty_counters (marked_at);
