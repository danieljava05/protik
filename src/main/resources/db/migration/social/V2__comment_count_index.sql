-- ADR 0017: counting a video's visible comments, replies included, is "WHERE video_id = ? AND status = 'VISIBLE'".
-- The existing indexes are partial on parent_id (top-level only, or replies only), so neither serves it.

CREATE INDEX comments_video_visible_idx ON social.comments (video_id) WHERE status = 'VISIBLE';
