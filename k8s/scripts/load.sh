#!/usr/bin/env bash
# Phase 16: steady traffic through the gateway while you change the cluster (rollout, pod kill, node
# drain, autoscaling), then a report of EVERY failed request. Zero-downtime is a number, not a feeling.
#
#   k8s/scripts/load.sh 120                     2 min: per user 8 reads/s + 1 transfer/s
#   USERS="alice bob" READS=9 k8s/scripts/load.sh 300
#
# Reads rotate over all five services. Transfers move 0.01 back and forth between two of the user's
# accounts, each with its own Idempotency-Key (Phase 13). Stay below the gateway's rate limit
# (10 req/s per user, Phase 8) or you measure 429s, not the cluster.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DURATION="${1:-60}"
USERS=(${USERS:-alice})
READS="${READS:-8}"
TRANSFERS="${TRANSFERS:-1}"
GATEWAY="${GATEWAY:-http://localhost:8080}"
PATHS=(/api/accounts /api/customers/me /api/transactions /api/notifications /api/payments)
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

login() { "$ROOT/infrastructure/keycloak/pkce-login.sh" "$1" 2>/dev/null; }

# Arrays indexed like USERS (macOS still ships bash 3.2: no associative arrays).
TOKEN=(); SRC=(); DST=()
refresh_tokens() {
  local u; for u in "${!USERS[@]}"; do TOKEN[$u]="$(login "${USERS[$u]}")"; done
}
refresh_tokens
for u in "${!USERS[@]}"; do
  # The user's two richest accounts: transfers ping-pong between them, so balances never run out.
  pair="$(curl -sf -H "Authorization: Bearer ${TOKEN[$u]}" "$GATEWAY/api/accounts" \
    | jq -r 'sort_by(-.balance) | "\(.[0].id),\(.[0].accountNumber) \(.[1].id),\(.[1].accountNumber)"')"
  SRC[$u]="${pair% *}"; DST[$u]="${pair#* }"
done

echo "Load: ${#USERS[@]} user(s) × ($READS reads + $TRANSFERS transfers)/s for ${DURATION}s against $GATEWAY"
START=$SECONDS; LAST_LOGIN=$SECONDS; TICK=0
while (( SECONDS - START < DURATION )); do
  (( SECONDS - LAST_LOGIN > 200 )) && { refresh_tokens; LAST_LOGIN=$SECONDS; }   # tokens live 5 min
  T=$((SECONDS - START))
  for u in "${!USERS[@]}"; do
    # One "-o /dev/null" PER URL: curl pairs each -o with one URL (a single -o covers only the first).
    urls=(); for ((i = 0; i < READS; i++)); do urls+=(-o /dev/null "$GATEWAY${PATHS[$(( (TICK + i) % ${#PATHS[@]} ))]}"); done
    curl -s --parallel --parallel-max "$READS" -m 25 \
      -H "Authorization: Bearer ${TOKEN[$u]}" -w "$T %{http_code} GET %{url_effective}\n" "${urls[@]}" >> "$OUT/$u.log" &
    for ((i = 0; i < TRANSFERS; i++)); do
      if (( (TICK + i) % 2 == 0 )); then from="${SRC[$u]}"; to="${DST[$u]}"; else from="${DST[$u]}"; to="${SRC[$u]}"; fi
      curl -s -o /dev/null -m 25 -X POST "$GATEWAY/api/payments" \
        -H "Authorization: Bearer ${TOKEN[$u]}" -H 'Content-Type: application/json' \
        -H "Idempotency-Key: $(uuidgen | tr 'A-Z' 'a-z')" \
        -d "{\"sourceAccountId\":\"${from%%,*}\",\"targetAccountNumber\":\"${to##*,}\",\"amount\":0.01,\"description\":\"load test\"}" \
        -w "$T %{http_code} POST /api/payments\n" >> "$OUT/$u.log" &
    done
  done
  TICK=$((TICK + 1))
  sleep 1
done
wait

cat "$OUT"/*.log > "$OUT/all"
TOTAL=$(wc -l < "$OUT/all" | tr -d ' ')
FAILED=$(awk '$2 !~ /^2/' "$OUT/all" | wc -l | tr -d ' ')
echo "Requests: $TOTAL   failed (non-2xx): $FAILED"
awk '{print $2}' "$OUT/all" | sort | uniq -c | sed 's/^/  /'
if [ "$FAILED" != 0 ]; then
  echo "Failed requests (second, status, request):"
  awk '$2 !~ /^2/' "$OUT/all" | sed "s|$GATEWAY||" | sort -n | sed 's/^/  /'
  exit 1
fi
