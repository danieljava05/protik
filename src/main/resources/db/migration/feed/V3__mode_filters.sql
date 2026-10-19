-- ADR 0014: "only creators you follow" restricts which videos appear, which a weight cannot
-- express. Modes stay data: this flag is part of the mode's published definition.

ALTER TABLE feed.ranking_modes ADD COLUMN followed_only boolean NOT NULL DEFAULT false;

UPDATE feed.ranking_modes SET followed_only = true WHERE code = 'FOLLOWING_ONLY';
