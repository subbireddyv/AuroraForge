#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# AuroraForge — Kafka topic initialisation
#
# Run this once after `docker compose up -d` to create all application topics.
# Topics are NOT auto-created (KAFKA_AUTO_CREATE_TOPICS_ENABLE=false) so this
# script is the single source of truth for topic names, partitions, and RF.
#
# Usage:
#   ./scripts/init-kafka.sh [BOOTSTRAP_SERVER]
#
# Default bootstrap server: localhost:29092
# ---------------------------------------------------------------------------
set -euo pipefail

BROKER="${1:-localhost:29092}"
KAFKA_TOPICS="docker exec -i auroraforge-kafka-1 kafka-topics.sh --bootstrap-server ${BROKER}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
die()     { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ---------------------------------------------------------------------------
# Wait until at least one broker is responsive
# ---------------------------------------------------------------------------
wait_for_kafka() {
    info "Waiting for Kafka at ${BROKER} ..."
    for i in $(seq 1 30); do
        if docker exec auroraforge-kafka-1 kafka-broker-api-versions.sh \
               --bootstrap-server "${BROKER}" >/dev/null 2>&1; then
            info "Kafka is ready (attempt ${i})"
            return 0
        fi
        sleep 2
    done
    die "Kafka did not become ready within 60 seconds."
}

# ---------------------------------------------------------------------------
# create_topic <name> <partitions> <replication-factor> [extra-config...]
# Idempotent: skips if topic already exists.
# ---------------------------------------------------------------------------
create_topic() {
    local name="$1" parts="$2" rf="$3"
    shift 3
    local extra_configs=("$@")

    if ${KAFKA_TOPICS} --describe --topic "${name}" >/dev/null 2>&1; then
        warn "Topic '${name}' already exists — skipping."
        return 0
    fi

    local config_args=()
    for cfg in "${extra_configs[@]}"; do
        config_args+=("--config" "${cfg}")
    done

    ${KAFKA_TOPICS} --create \
        --topic "${name}" \
        --partitions "${parts}" \
        --replication-factor "${rf}" \
        "${config_args[@]}"

    info "Created topic '${name}' (partitions=${parts} rf=${rf})"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
wait_for_kafka

info "=== Creating AuroraForge Kafka topics ==="

#
# Core event pipeline
# partitions=12 → allows 12 parallel consumer threads per service group
# rf=3          → survives single-broker loss in the 3-broker local cluster
#
create_topic "auroraforge.events.raw"       12 3 \
    "retention.ms=604800000" \
    "compression.type=lz4" \
    "min.insync.replicas=2"

create_topic "auroraforge.events.enriched"  12 3 \
    "retention.ms=604800000" \
    "compression.type=lz4" \
    "min.insync.replicas=2"

create_topic "auroraforge.events.processed" 12 3 \
    "retention.ms=604800000" \
    "compression.type=lz4" \
    "min.insync.replicas=2"

#
# Internal Kafka Streams topics (used by EventStreamTopology state stores)
# Lower partitions — Streams repartitions to match the source topic count.
#
create_topic "auroraforge.events.aggregated.counts" 6 3 \
    "retention.ms=86400000" \
    "compression.type=lz4"

#
# Sync commands (reserved for future orchestration use)
#
create_topic "auroraforge.sync.commands" 6 3 \
    "retention.ms=604800000"

#
# Dead Letter Queues — lower partitions, longer retention for debugging
#
create_topic "auroraforge.dlq"                  3 3 \
    "retention.ms=2592000000" \
    "compression.type=lz4"

create_topic "auroraforge.events.raw.DLQ"       3 3 \
    "retention.ms=2592000000" \
    "compression.type=lz4"

create_topic "auroraforge.events.enriched.DLQ"  3 3 \
    "retention.ms=2592000000" \
    "compression.type=lz4"

#
# Transactional outbox CDC topic (written by Debezium from auroraforge.outbox_events table)
# Matches Debezium connector topic naming: {logical-name}.{schema}.{table}
#
create_topic "auroraforge.public.outbox_events" 12 3 \
    "retention.ms=604800000" \
    "cleanup.policy=delete"

info ""
info "=== Topic summary ==="
docker exec auroraforge-kafka-1 kafka-topics.sh \
    --bootstrap-server "${BROKER}" \
    --list \
    | grep "^auroraforge\." \
    | sort

info "=== Done ==="
