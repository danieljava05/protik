-- Ranking modes are defined by published weights (VISION.md goal 1).

INSERT INTO feed.ranking_components (code, description) VALUES
  ('RECENCY','How new the video is'),
  ('ENGAGEMENT','Likes, comments and views relative to age'),
  ('FOLLOWING','Whether you follow the creator'),
  ('TOPIC','Overlap with hashtags you engage with');

INSERT INTO feed.ranking_modes (code, description) VALUES
  ('DEFAULT','Balanced mix of all components'),
  ('CHRONOLOGICAL','Newest first'),
  ('FOLLOWING_ONLY','Only creators you follow'),
  ('TOPIC_FOCUSED','Favours topics you engage with');

INSERT INTO feed.ranking_mode_weights (mode_code, component_code, weight) VALUES
  ('DEFAULT','RECENCY',0.300),('DEFAULT','ENGAGEMENT',0.300),('DEFAULT','FOLLOWING',0.200),('DEFAULT','TOPIC',0.200),
  ('CHRONOLOGICAL','RECENCY',1.000),('CHRONOLOGICAL','ENGAGEMENT',0),('CHRONOLOGICAL','FOLLOWING',0),('CHRONOLOGICAL','TOPIC',0),
  ('FOLLOWING_ONLY','RECENCY',0.500),('FOLLOWING_ONLY','ENGAGEMENT',0),('FOLLOWING_ONLY','FOLLOWING',1.000),('FOLLOWING_ONLY','TOPIC',0),
  ('TOPIC_FOCUSED','RECENCY',0.200),('TOPIC_FOCUSED','ENGAGEMENT',0.200),('TOPIC_FOCUSED','FOLLOWING',0.100),('TOPIC_FOCUSED','TOPIC',0.500);
