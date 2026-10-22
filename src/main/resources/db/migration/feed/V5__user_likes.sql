-- ADR 0016 decision 4: the viewer's interests are derived from what they liked. The feed keeps its
-- own copy of "who liked which candidate" (fed by VideoLiked / VideoUnliked) and joins it to the
-- candidates' tags. A like of a video that is not (or no longer) a candidate is not kept.

CREATE TABLE feed.user_likes (
  user_id  uuid NOT NULL,
  video_id uuid NOT NULL REFERENCES feed.candidates(video_id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, video_id)
);

-- FK index: removing a candidate removes the likes pointing at it.
CREATE INDEX user_likes_video_idx ON feed.user_likes (video_id);
