#!/usr/bin/env bash
# Phase 15: checks the RUNNING container stack (docker compose --profile app up -d --build):
# health, non-root, secrets not in the environment, JVM sized by the container limit, only the
# gateway and frontend reachable from the host, and a request through the whole chain.
set -uo pipefail

SERVICES=(user-service account-service payment-service transaction-service notification-service api-gateway)
FAILED=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
check() { if eval "$2" >/dev/null 2>&1; then pass "$1"; else fail "$1"; fi; }

echo "Containers"
for s in "${SERVICES[@]}" frontend; do
  c="banking-$s"
  check "$c is healthy" "[ \"\$(docker inspect -f '{{.State.Health.Status}}' $c)\" = healthy ]"
done

echo "Images and runtime"
for s in "${SERVICES[@]}"; do
  c="banking-$s"
  check "$c runs as uid 10001 (not root)" "[ \"\$(docker exec $c id -u)\" = 10001 ]"
  check "$c has no password/secret in its environment" \
    "! docker inspect $c --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -qiE 'password|secret'"
  # A JVM started in the container with the image's JAVA_TOOL_OPTIONS: 75 % of 512 MiB = 402653184 bytes.
  check "$c heap = 75 % of its 512 MiB limit" \
    "[ \"\$(docker exec $c java -XX:+PrintFlagsFinal -version 2>/dev/null | awk '/ MaxHeapSize /{print \$4}')\" = 402653184 ]"
done
check "frontend runs as non-root" "[ \"\$(docker exec banking-frontend id -u)\" != 0 ]"
check "no .env inside an image" "! docker run --rm --entrypoint sh banking/payment-service:\${IMAGE_TAG:-local} -c 'find / -name .env 2>/dev/null' | grep -q ."

echo "Network exposure"
for port in 8081 8082 8083 8084 8085; do
  check "service port $port is NOT reachable from the host" "! curl -s -m 2 localhost:$port"
done
check "gateway answers on :8080 (401 without token)" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/accounts)\" = 401 ]"
check "gateway returns an X-Trace-Id" "curl -sI localhost:8080/api/accounts | grep -qi '^x-trace-id'"
check "frontend serves deep links (SPA fallback) on :4200" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' localhost:4200/payments)\" = 200 ]"
check "frontend sends a Content-Security-Policy" "curl -sI localhost:4200/ | grep -qi '^content-security-policy'"

echo "Observability"
check "Prometheus scrapes all 6 containers" \
  "[ \"\$(curl -s localhost:9090/api/v1/targets | jq '[.data.activeTargets[] | select(.labels.job==\"banking-services\" and .health==\"up\")] | length')\" = 6 ]"
check "Loki has logs of all 6 services" \
  "[ \"\$(curl -s localhost:3100/loki/api/v1/label/service/values | jq '.data | length')\" -ge 6 ]"

echo
if [ $FAILED = 0 ]; then echo "All checks passed."; else echo "Some checks FAILED."; exit 1; fi
