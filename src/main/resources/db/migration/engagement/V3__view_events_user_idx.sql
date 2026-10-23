-- Serves deleting everything one user viewed (account deletion, ADR 0020).
CREATE INDEX view_events_user_idx ON engagement.view_events (user_id) WHERE user_id IS NOT NULL;
