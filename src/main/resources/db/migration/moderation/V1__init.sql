-- Module migration. Source design: docs/schema.md, ADR 0005.
-- No foreign keys to other modules' schemas (enforced by SchemaInvariantsTest).

CREATE TABLE moderation.reports (
  id          uuid PRIMARY KEY,
  reporter_id uuid        NOT NULL,
  target_type text        NOT NULL CHECK (target_type IN ('VIDEO','COMMENT','ACCOUNT')),
  target_id   uuid        NOT NULL,
  reason_code text        NOT NULL
                CHECK (reason_code IN ('CHILD_SAFETY','COPYRIGHT','HARASSMENT','VIOLENCE','SPAM','OTHER')),
  details     text        NOT NULL DEFAULT '' CHECK (length(details) <= 2000),
  status      text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACTIONED','DISMISSED')),
  created_at  timestamptz NOT NULL DEFAULT now()
);
-- One open report per reporter per target: stops report-flooding
CREATE UNIQUE INDEX reports_open_unique_idx
  ON moderation.reports (reporter_id, target_type, target_id) WHERE status = 'OPEN';
-- Serves the moderator queue (oldest open first)
CREATE INDEX reports_queue_idx ON moderation.reports (created_at, id) WHERE status = 'OPEN';

-- Append-only audit log: every state-changing action, with actor, time, reason.
CREATE TABLE moderation.actions (
  id          uuid PRIMARY KEY,
  report_id   uuid        REFERENCES moderation.reports(id),
  actor_id    uuid        NOT NULL,       -- pseudonymous id only; no personal data
  action_type text        NOT NULL
                CHECK (action_type IN ('TAKEDOWN','RESTORE','SUSPEND_ACCOUNT','DISMISS_REPORT','HIDE_COMMENT')),
  target_type text        NOT NULL CHECK (target_type IN ('VIDEO','COMMENT','ACCOUNT')),
  target_id   uuid        NOT NULL,
  reason      text        NOT NULL CHECK (length(btrim(reason)) > 0),
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX actions_report_idx ON moderation.actions (report_id);
-- Serves "history of a target"
CREATE INDEX actions_target_idx ON moderation.actions (target_type, target_id, occurred_at);

CREATE FUNCTION moderation.forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'moderation.actions is append-only' USING ERRCODE = 'P0001';
END $$;
CREATE TRIGGER actions_append_only BEFORE UPDATE OR DELETE ON moderation.actions
  FOR EACH ROW EXECUTE FUNCTION moderation.forbid_change();
CREATE TRIGGER actions_no_truncate BEFORE TRUNCATE ON moderation.actions
  FOR EACH STATEMENT EXECUTE FUNCTION moderation.forbid_change();
