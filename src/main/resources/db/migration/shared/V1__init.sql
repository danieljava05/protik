-- Module migration. Source design: docs/schema.md, ADR 0005.
-- No foreign keys to other modules' schemas (enforced by SchemaInvariantsTest).

-- Extensions are installed into public so every module can use them; each
-- module's Flyway run has public on its search_path. citext, unaccent and
-- pg_trgm are "trusted" extensions (PostgreSQL 13+), so a non-superuser
-- database owner can create them.
CREATE EXTENSION IF NOT EXISTS citext SCHEMA public;
CREATE EXTENSION IF NOT EXISTS unaccent SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;

-- unaccent() is only STABLE, so it cannot be used in generated columns or
-- index expressions. This wrapper is the standard workaround; it is safe
-- as long as the unaccent dictionary is never changed after data exists.
CREATE FUNCTION shared.immutable_unaccent(text) RETURNS text
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

CREATE TABLE shared.jobs (
  id               uuid PRIMARY KEY,
  type             text        NOT NULL,
  payload          jsonb       NOT NULL DEFAULT '{}',
  status           text        NOT NULL DEFAULT 'QUEUED'
                     CHECK (status IN ('QUEUED','RUNNING','DONE','DEAD')),
  attempts         int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  max_attempts     int         NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
  run_at           timestamptz NOT NULL DEFAULT now(),
  lease_expires_at timestamptz,
  last_error       text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'RUNNING') = (lease_expires_at IS NOT NULL))
);
-- Serves claiming: SELECT ... WHERE status='QUEUED' AND run_at<=now()
--   ORDER BY run_at FOR UPDATE SKIP LOCKED
CREATE INDEX jobs_claim_idx ON shared.jobs (run_at) WHERE status = 'QUEUED';
-- Serves lease recovery: re-queue RUNNING jobs whose lease expired
CREATE INDEX jobs_lease_idx ON shared.jobs (lease_expires_at) WHERE status = 'RUNNING';

CREATE TABLE shared.idempotency_keys (
  user_id         uuid        NOT NULL,
  key             text        NOT NULL CHECK (length(key) BETWEEN 1 AND 255),
  endpoint        text        NOT NULL,
  request_hash    bytea       NOT NULL,   -- same key + different hash is rejected
  response_status int,                    -- NULL while the first request is in flight
  response_body   jsonb,
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,
  PRIMARY KEY (user_id, key)
);
-- Serves the periodic cleanup of expired keys
CREATE INDEX idempotency_expiry_idx ON shared.idempotency_keys (expires_at);
