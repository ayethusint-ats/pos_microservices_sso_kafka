#!/bin/bash
# ---------------------------------------------------------------------------
# Topics as code. Run once by the "kafka-init" Compose service after the broker is healthy.
# Idempotent (--if-not-exists), so it is safe to run on every `docker compose up`.
#
# The broker has auto.create.topics.enable=false: a typo in a topic name fails loudly
# instead of silently creating a new, empty topic nobody reads.
#
# Naming: banking.<what>.  Partitions: 3 (up to 3 consumers per group in parallel).
# Replication factor 1 because there is ONE broker locally. Production: RF 3, min.insync.replicas 2.
# ---------------------------------------------------------------------------
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP_SERVERS:-kafka:29092}"
TOPICS=/opt/kafka/bin/kafka-topics.sh

create() {
  local name="$1"; shift
  echo "topic ${name}"
  "$TOPICS" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$name" --partitions 3 --replication-factor 1 "$@"
}

# Every balance change, published by account-service. Key = account ID (per-account ordering).
# Kept 30 days: transaction-service can be rebuilt by replaying it.
create banking.account-transactions --config retention.ms=2592000000
# Payment outcomes, published by payment-service. Key = payment ID.
create banking.payment-events       --config retention.ms=2592000000
# Current state of each customer profile, published by user-service. Key = Keycloak user ID.
# COMPACTED: Kafka keeps at least the latest value per key forever, so a consumer reading from
# the beginning gets the current profile of every customer (a "table" in a topic).
create banking.customer-events      --config cleanup.policy=compact --config min.compaction.lag.ms=0

# Dead-letter topics: messages a consumer could not process even after retries.
# Same partition count as the source topic (Spring's recoverer keeps the partition number).
create banking.account-transactions.DLT
create banking.payment-events.DLT
create banking.customer-events.DLT

"$TOPICS" --bootstrap-server "$BOOTSTRAP" --list
