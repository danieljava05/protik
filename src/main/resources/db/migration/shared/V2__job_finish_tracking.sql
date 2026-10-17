-- ADR 0008: track when a job finished so DONE jobs can be purged and DEAD
-- jobs can be inspected. Added as a new migration, never by editing V1.

ALTER TABLE shared.jobs ADD COLUMN finished_at timestamptz;

-- Rows that already finished before this migration get a timestamp.
UPDATE shared.jobs SET finished_at = created_at WHERE status IN ('DONE', 'DEAD');

ALTER TABLE shared.jobs
  ADD CONSTRAINT jobs_finished_at_matches_status
  CHECK ((status IN ('DONE', 'DEAD')) = (finished_at IS NOT NULL)),
  ADD CONSTRAINT jobs_type_length CHECK (length(type) BETWEEN 1 AND 100);

-- Serves the periodic purge of old DONE jobs (DELETE ... WHERE status='DONE'
-- AND finished_at < now() - retention).
CREATE INDEX jobs_done_purge_idx ON shared.jobs (finished_at) WHERE status = 'DONE';
