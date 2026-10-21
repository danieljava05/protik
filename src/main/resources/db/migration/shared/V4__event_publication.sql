-- ADR 0002 / ADR 0015: Spring Modulith's persistent event publication registry.
-- An event is stored in the publisher's transaction and removed when every listener has
-- handled it, so a crash between "committed" and "handled" does not lose it (at-least-once).
-- Structure copied from spring-modulith-events-jdbc 2.1.1 (schemas/v2/schema-postgresql.sql);
-- it lives in the shared schema via spring.modulith.events.jdbc.schema=shared.

CREATE TABLE shared.event_publication (
  id                     uuid        NOT NULL,
  listener_id            text        NOT NULL,
  event_type             text        NOT NULL,
  serialized_event       text        NOT NULL,
  publication_date       timestamptz NOT NULL,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    int,
  last_resubmission_date timestamptz,
  PRIMARY KEY (id)
);

-- Both indexes are queried by Modulith itself: the hash index to find a publication by its event
-- payload, the completion-date index to list incomplete publications on restart.
CREATE INDEX event_publication_serialized_event_hash_idx ON shared.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON shared.event_publication (completion_date);
