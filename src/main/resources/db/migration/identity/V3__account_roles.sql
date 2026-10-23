-- Staff roles (ADR 0019 decision 1). No API grants these yet: a database operator inserts them.
CREATE TABLE identity.account_roles (
  account_id uuid        NOT NULL REFERENCES identity.accounts(id) ON DELETE CASCADE,
  role       text        NOT NULL CHECK (role IN ('MODERATOR','ADMIN')),
  granted_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, role)
);
