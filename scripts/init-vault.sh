#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# AuroraForge – Vault initialisation (local dev / docker-compose)
#
# Enables the Transit secrets engine and creates the encryption key used by
# auroraforge-keymgmt when CLOUD_PROVIDER=LOCAL.
#
# Also seeds the KV v2 engine with placeholder credentials so all services
# can start without real cloud credentials.
#
# Usage:
#   ./scripts/init-vault.sh [VAULT_ADDR] [VAULT_TOKEN]
#
# Defaults:
#   VAULT_ADDR  = http://localhost:8200
#   VAULT_TOKEN = auroraforge-dev
# ---------------------------------------------------------------------------
set -euo pipefail

export VAULT_ADDR="${1:-http://localhost:8200}"
export VAULT_TOKEN="${2:-auroraforge-dev}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
die()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ---------------------------------------------------------------------------
wait_for_vault() {
    info "Waiting for Vault at ${VAULT_ADDR} ..."
    for i in $(seq 1 30); do
        if vault status >/dev/null 2>&1; then
            info "Vault is ready (attempt ${i})"
            return 0
        fi
        sleep 2
    done
    die "Vault did not become ready within 60 seconds. Is docker compose running?"
}

enable_engine_if_missing() {
    local path="$1" type="$2"
    if vault secrets list -format=json | grep -q "\"${path}/\""; then
        warn "Secret engine '${path}' already enabled — skipping."
    else
        vault secrets enable -path="${path}" "${type}"
        info "Enabled secret engine: ${path} (${type})"
    fi
}

# ---------------------------------------------------------------------------
wait_for_vault

info "=== Enabling secret engines ==="
enable_engine_if_missing "transit" "transit"
enable_engine_if_missing "secret"  "kv"

# KV engine is auto-enabled in dev mode — upgrade to v2 if needed
vault kv enable-versioning secret/ 2>/dev/null || true

# ---------------------------------------------------------------------------
info "=== Creating Transit encryption keys ==="

create_transit_key() {
    local name="$1" type="${2:-aes256-gcm96}"
    if vault read "transit/keys/${name}" >/dev/null 2>&1; then
        warn "Transit key '${name}' already exists — skipping."
    else
        vault write -f "transit/keys/${name}" type="${type}"
        info "Created transit key: ${name} (${type})"
    fi
}

create_transit_key "auroraforge-app-key"
create_transit_key "auroraforge-restricted-key"    # separate key for RESTRICTED class
create_transit_key "auroraforge-confidential-key"  # separate key for CONFIDENTIAL class

# ---------------------------------------------------------------------------
info "=== Creating Vault policies ==="

vault policy write auroraforge-keymgmt - <<'POLICY'
# Key Management Service — encrypt/decrypt only, no key management
path "transit/encrypt/auroraforge-*" {
  capabilities = ["update"]
}
path "transit/decrypt/auroraforge-*" {
  capabilities = ["update"]
}
path "transit/keys/auroraforge-*" {
  capabilities = ["read"]
}
POLICY
info "Policy 'auroraforge-keymgmt' written."

vault policy write auroraforge-app - <<'POLICY'
path "secret/data/auroraforge/*" {
  capabilities = ["read"]
}
POLICY
info "Policy 'auroraforge-app' written."

# ---------------------------------------------------------------------------
info "=== Seeding KV secrets (placeholder values for local dev) ==="

seed_secret() {
    local path="$1"; shift
    vault kv put "secret/auroraforge/${path}" "$@" >/dev/null
    info "Seeded: secret/auroraforge/${path}"
}

seed_secret "postgres"    password="auroraforge_dev_secret"
seed_secret "redis"       password="redis_dev_secret"
seed_secret "minio"       access_key="minioadmin" secret_key="minioadmin_secret"

# Generate a local RSA-4096 key pair for JWT signing
info "Generating RSA-4096 key pair for JWT signing..."
PRIVATE_KEY=$(openssl genrsa 4096 2>/dev/null | base64 -w0)
PUBLIC_KEY=$(echo "${PRIVATE_KEY}" | base64 -d | openssl rsa -pubout 2>/dev/null | base64 -w0)
seed_secret "auth" \
    jwt_rsa_private_key="${PRIVATE_KEY}" \
    jwt_rsa_public_key="${PUBLIC_KEY}"

# ---------------------------------------------------------------------------
info ""
info "=== Vault initialisation complete ==="
info ""
info "Vault address:  ${VAULT_ADDR}"
info "Transit keys:   auroraforge-app-key, auroraforge-restricted-key, auroraforge-confidential-key"
info "KV secrets:     secret/auroraforge/{postgres,redis,minio,auth}"
info ""
info "Services can now connect with:"
info "  VAULT_ADDR=${VAULT_ADDR}"
info "  VAULT_TOKEN=${VAULT_TOKEN}"
