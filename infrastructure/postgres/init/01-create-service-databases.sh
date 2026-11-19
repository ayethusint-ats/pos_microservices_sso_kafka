#!/bin/bash
# ---------------------------------------------------------------------------
# Database-per-service bootstrap. It runs ONCE, when the Postgres volume is empty.
#
# For every service it creates:
#   - a login role (user_svc, account_svc, ...) with its own password
#   - a database owned by that role
#   - REVOKE ALL ... FROM PUBLIC, so no other role can even CONNECT to it
#
# Result: user-service's credentials can't read account_db, and vice versa.
# The services share a Postgres *server* (cheap for local dev), not *data*.
#
# Passwords come from environment variables (see .env), never from this file.
# Only tables are missing: each service creates its own with Flyway migrations.
# ---------------------------------------------------------------------------
set -euo pipefail

create_service_database() {
  local db_name="$1" role_name="$2" role_password="$3"

  echo "Creating database '${db_name}' owned by role '${role_name}'"

  # psql variables (:"name" for identifiers, :'name' for literals) quote safely,
  # so a password containing quotes can't break or inject into the SQL.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db_name="$db_name" -v role_name="$role_name" -v role_password="$role_password" <<'EOSQL'
CREATE ROLE :"role_name" WITH LOGIN PASSWORD :'role_password';
CREATE DATABASE :"db_name" OWNER :"role_name";
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
EOSQL
}

create_service_database user_db        user_svc        "${USER_DB_PASSWORD:?USER_DB_PASSWORD is not set}"
create_service_database account_db     account_svc     "${ACCOUNT_DB_PASSWORD:?ACCOUNT_DB_PASSWORD is not set}"
create_service_database payment_db     payment_svc     "${PAYMENT_DB_PASSWORD:?PAYMENT_DB_PASSWORD is not set}"
create_service_database transaction_db transaction_svc "${TRANSACTION_DB_PASSWORD:?TRANSACTION_DB_PASSWORD is not set}"
