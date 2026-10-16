# Schema draft

`schema.sql` is the reviewed design baseline for the database
([ADR 0005](../adr/0005-database-structure.md)). It is **not** a Flyway
migration: real migrations are written per ticket. `tests.sql` checks
it: constraints reject bad data, the audit log is append-only, deleting
a video records every storage key, Yoruba text is searchable without
tone marks, and structural rules hold (no foreign key crosses a module,
every foreign key has an index, every table has a primary key).

Run (needs Docker): `./run-tests.sh`

Verified on PostgreSQL 17 only. Requires 15+ (`UNIQUE NULLS NOT DISTINCT`).
These tests prove the rules are enforced; they say nothing about
performance. No query plan, storage size or load figure has been measured.
