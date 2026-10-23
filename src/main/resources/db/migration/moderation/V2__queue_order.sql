-- The queue shows child-safety reports first, then oldest first (ADR 0019 decision 3).
CREATE INDEX reports_priority_queue_idx
  ON moderation.reports ((CASE WHEN reason_code = 'CHILD_SAFETY' THEN 0 ELSE 1 END), created_at, id)
  WHERE status = 'OPEN';
