#!/usr/bin/env bash
# Loads schema.sql into a throwaway PostgreSQL 17 container and runs tests.sql.
# Requires Docker. Usage: ./run-tests.sh
set -euo pipefail
cd "$(dirname "$0")"
name=protik-schema-test
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker rm -f "$name" >/dev/null 2>&1 || true
docker run -d --name "$name" -e POSTGRES_PASSWORD=x postgres:17 >/dev/null
until docker exec "$name" psql -U postgres -c 'select 1' >/dev/null 2>&1; do sleep 1; done
docker exec -i "$name" psql -U postgres -v ON_ERROR_STOP=1 -q < schema.sql
docker exec -i "$name" psql -U postgres -q -t -A < tests.sql | grep -v '^$'
