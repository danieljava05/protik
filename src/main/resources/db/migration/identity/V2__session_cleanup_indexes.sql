-- ADR 0012: serve the scheduled purge of finished sessions.
-- (DELETE FROM identity.session_families WHERE revoked_at < ... OR created_at < ...)

CREATE INDEX session_families_revoked_idx ON identity.session_families (revoked_at)
  WHERE revoked_at IS NOT NULL;

CREATE INDEX session_families_created_idx ON identity.session_families (created_at);
