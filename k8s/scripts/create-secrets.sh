#!/usr/bin/env bash
# Phase 16: Kubernetes Secrets from the git-ignored .env. Nothing secret is ever in k8s/.
#
# One Secret per consumer, with only the keys it needs (least privilege): user-service's pod
# can't mount payment-service's credentials. Re-running updates them (apply).
#
# Values never appear on a command line (visible in `ps`): each Secret is fed through a pipe.
# A changed Secret is NOT picked up by running pods (Spring reads it at startup):
#   kubectl -n banking rollout restart deployment/<service>
#
# Production: Secrets come from a vault (External Secrets Operator + AWS Secrets Manager / Vault,
# or the Secrets Store CSI driver), with encryption at rest in etcd and RBAC on "get secrets".
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
[ -f "$ENV_FILE" ] || { echo "No $ENV_FILE (cp .env.example .env, then set real values)" >&2; exit 1; }

# Read one variable from .env WITHOUT sourcing it (sourcing would execute anything in the file).
env_value() {
  local line value
  line="$(grep -E "^$1=" "$ENV_FILE" | tail -n 1)" || { echo "$1 is missing in $ENV_FILE" >&2; exit 1; }
  value="${line#*=}"
  value="${value%\"}"; value="${value#\"}"
  [ -n "$value" ] || { echo "$1 is empty in $ENV_FILE" >&2; exit 1; }
  printf '%s' "$value"
}

# secret <namespace> <name> KEY_IN_SECRET=ENV_VARIABLE ...
secret() {
  local namespace="$1" name="$2"; shift 2
  local pair
  {
    for pair in "$@"; do
      printf '%s=%s\n' "${pair%%=*}" "$(env_value "${pair#*=}")"
    done
  } | kubectl -n "$namespace" create secret generic "$name" --from-env-file=/dev/stdin \
        --dry-run=client -o yaml \
    | kubectl label --local -f - app.kubernetes.io/part-of=banking-platform -o yaml \
    | kubectl apply -f - >/dev/null
  echo "  secret $namespace/$name ($# keys)"
}

echo "Creating Secrets from $ENV_FILE"
# Postgres: admin + the per-service passwords its init scripts give the roles.
secret banking-infra postgres-init \
  POSTGRES_USER=POSTGRES_ADMIN_USER POSTGRES_PASSWORD=POSTGRES_ADMIN_PASSWORD \
  USER_DB_PASSWORD=USER_DB_PASSWORD ACCOUNT_DB_PASSWORD=ACCOUNT_DB_PASSWORD \
  PAYMENT_DB_PASSWORD=PAYMENT_DB_PASSWORD TRANSACTION_DB_PASSWORD=TRANSACTION_DB_PASSWORD \
  KEYCLOAK_DB_PASSWORD=KEYCLOAK_DB_PASSWORD
# Keycloak: admin bootstrap, its DB password, and the ${...} placeholders of the realm JSON.
secret banking-infra keycloak \
  KC_BOOTSTRAP_ADMIN_USERNAME=KEYCLOAK_ADMIN KC_BOOTSTRAP_ADMIN_PASSWORD=KEYCLOAK_ADMIN_PASSWORD \
  KC_DB_PASSWORD=KEYCLOAK_DB_PASSWORD \
  PAYMENT_SERVICE_CLIENT_SECRET=PAYMENT_SERVICE_CLIENT_SECRET DEMO_USER_PASSWORD=DEMO_USER_PASSWORD
# The services: mounted as files /run/secrets/<KEY> (Spring configtree).
secret banking user-service-secrets        USER_DB_PASSWORD=USER_DB_PASSWORD
secret banking account-service-secrets     ACCOUNT_DB_PASSWORD=ACCOUNT_DB_PASSWORD
secret banking payment-service-secrets     PAYMENT_DB_PASSWORD=PAYMENT_DB_PASSWORD \
                                           PAYMENT_SERVICE_CLIENT_SECRET=PAYMENT_SERVICE_CLIENT_SECRET
secret banking transaction-service-secrets TRANSACTION_DB_PASSWORD=TRANSACTION_DB_PASSWORD
