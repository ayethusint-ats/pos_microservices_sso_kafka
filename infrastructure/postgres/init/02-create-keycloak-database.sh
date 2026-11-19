#!/bin/bash
# ---------------------------------------------------------------------------
# Keycloak is a stateful service too (users, sessions, realm configuration).
# Same rule as every service: its own database and role, invisible to everyone else.
#
# Runs automatically on a fresh volume. On an existing volume, run it once by hand:
#   docker compose up -d postgres        # recreates the container with the new env var
#   docker exec banking-postgres bash /docker-entrypoint-initdb.d/02-create-keycloak-database.sh
# It is idempotent: running it again does nothing.
# ---------------------------------------------------------------------------
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v role_password="${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD is not set}" <<'EOSQL'
SELECT 'CREATE ROLE keycloak_svc WITH LOGIN PASSWORD ' || quote_literal(:'role_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'keycloak_svc') \gexec

SELECT 'CREATE DATABASE keycloak_db OWNER keycloak_svc'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak_db') \gexec

REVOKE ALL ON DATABASE keycloak_db FROM PUBLIC;
EOSQL

echo "Database 'keycloak_db' owned by role 'keycloak_svc' is ready"
