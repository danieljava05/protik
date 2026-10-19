-- ADR 0013: fixed-window counters for rate limiting. One row per hashed key.
-- Keys are SHA-256 hex digests, so no email address or IP address is stored.

CREATE TABLE shared.rate_limits (
  key          text        PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 100),
  window_start timestamptz NOT NULL,
  count        int         NOT NULL CHECK (count >= 0)
);

-- Serves the periodic purge of windows that ended long ago.
CREATE INDEX rate_limits_window_idx ON shared.rate_limits (window_start);
