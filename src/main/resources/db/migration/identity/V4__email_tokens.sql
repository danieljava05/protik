-- Single-use tokens for email verification and password reset (ADR 0021). Only the hash is stored.
CREATE TABLE identity.email_tokens (
  id         uuid PRIMARY KEY,
  account_id uuid        NOT NULL REFERENCES identity.accounts(id) ON DELETE CASCADE,
  purpose    text        NOT NULL CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
  token_hash bytea       NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at    timestamptz,
  CHECK (expires_at > created_at)
);
-- Serves "invalidate this account's unused tokens of a purpose" and the FK cascade
CREATE INDEX email_tokens_account_idx ON identity.email_tokens (account_id, purpose) WHERE used_at IS NULL;
-- Serves the purge of old rows
CREATE INDEX email_tokens_expiry_idx ON identity.email_tokens (expires_at);

-- A password reset ends the person's sessions
ALTER TABLE identity.session_families DROP CONSTRAINT session_families_revoked_reason_check;
ALTER TABLE identity.session_families ADD CONSTRAINT session_families_revoked_reason_check
  CHECK (revoked_reason IN ('LOGOUT','TOKEN_REUSE','ADMIN','ACCOUNT_DELETION','PASSWORD_RESET'));
