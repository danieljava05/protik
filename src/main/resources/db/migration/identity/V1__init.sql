-- Module migration. Source design: docs/schema.md, ADR 0005.
-- No foreign keys to other modules' schemas (enforced by SchemaInvariantsTest).

CREATE TABLE identity.accounts (
  id         uuid PRIMARY KEY,
  username   citext      NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_.]{3,30}$'),
  status     text        NOT NULL DEFAULT 'ACTIVE'
               CHECK (status IN ('ACTIVE','SUSPENDED','DELETION_PENDING')),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- A user may have several login identifiers (many Nigerian users sign in
-- with a phone number). Kept apart from the account: 3NF.
CREATE TABLE identity.login_identifiers (
  id          uuid PRIMARY KEY,
  account_id  uuid        NOT NULL REFERENCES identity.accounts(id) ON DELETE CASCADE,
  kind        text        NOT NULL CHECK (kind IN ('EMAIL','PHONE')),
  value       citext      NOT NULL,
  verified_at timestamptz,
  UNIQUE (kind, value)
);
-- FK index: cascade delete and "identifiers of an account"
CREATE INDEX login_identifiers_account_idx ON identity.login_identifiers (account_id);

CREATE TABLE identity.password_credentials (
  account_id    uuid PRIMARY KEY REFERENCES identity.accounts(id) ON DELETE CASCADE,
  password_hash text        NOT NULL,   -- Argon2/bcrypt output only
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity.profiles (
  account_id         uuid PRIMARY KEY REFERENCES identity.accounts(id) ON DELETE CASCADE,
  display_name       text NOT NULL DEFAULT '' CHECK (length(display_name) <= 50),
  bio                text NOT NULL DEFAULT '' CHECK (length(bio) <= 300),
  avatar_key         text,
  preferred_language text
);

CREATE TABLE identity.session_families (
  id             uuid PRIMARY KEY,
  account_id     uuid        NOT NULL REFERENCES identity.accounts(id) ON DELETE CASCADE,
  created_at     timestamptz NOT NULL DEFAULT now(),
  revoked_at     timestamptz,
  revoked_reason text CHECK (revoked_reason IN ('LOGOUT','TOKEN_REUSE','ADMIN','ACCOUNT_DELETION')),
  CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
-- Serves "log out everywhere" and revoke-on-deletion
CREATE INDEX session_families_account_idx ON identity.session_families (account_id);

CREATE TABLE identity.refresh_tokens (
  id         uuid PRIMARY KEY,
  family_id  uuid        NOT NULL REFERENCES identity.session_families(id) ON DELETE CASCADE,
  token_hash bytea       NOT NULL UNIQUE,   -- hash only; the token itself is never stored
  issued_at  timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  rotated_at timestamptz,                   -- set when exchanged; presenting it again = reuse
  CHECK (expires_at > issued_at)
);
-- FK index: revoke every token in a family
CREATE INDEX refresh_tokens_family_idx ON identity.refresh_tokens (family_id);

-- Account deletion across modules without cross-schema FKs (ADR 0005 #5)
CREATE TABLE identity.deletion_requests (
  id           uuid PRIMARY KEY,
  account_id   uuid        NOT NULL UNIQUE,   -- no FK: the account row is purged last
  requested_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz
);
CREATE TABLE identity.deletion_acks (
  request_id uuid        NOT NULL REFERENCES identity.deletion_requests(id) ON DELETE CASCADE,
  module     text        NOT NULL
               CHECK (module IN ('video','feed','social','engagement','moderation')),
  acked_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (request_id, module)
);
