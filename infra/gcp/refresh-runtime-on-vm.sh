#!/usr/bin/env bash

set -euo pipefail

METADATA_URL="http://metadata.google.internal/computeMetadata/v1/instance/attributes"

metadata() {
  curl -fsSL -H "Metadata-Flavor: Google" "${METADATA_URL}/$1"
}

metadata_optional() {
  curl -fsS -H "Metadata-Flavor: Google" "${METADATA_URL}/$1" 2>/dev/null || true
}

compose_cmd() {
  if docker compose version >/dev/null 2>&1; then
    echo "docker compose"
    return
  fi

  if command -v docker-compose >/dev/null 2>&1; then
    echo "docker-compose"
    return
  fi

  echo "No Docker Compose command found" >&2
  exit 1
}

require_env() {
  local name=$1
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}" >&2
    exit 1
  fi
}

require_env BACKEND_IMAGE
require_env FRONTEND_IMAGE
require_env IBKR_GATEWAY_IMAGE

CONFIG_BUCKET=${CONFIG_BUCKET:-$(metadata config-bucket)}
PROJECT_ID=${PROJECT_ID:-$(metadata project-id)}
DB_NAME=${DB_NAME:-$(metadata db-name)}
DB_USERNAME_SECRET=${DB_USERNAME_SECRET:-$(metadata db-username-secret)}
DB_PASSWORD_SECRET=${DB_PASSWORD_SECRET:-$(metadata db-password-secret)}
GEMINI_API_KEY_SECRET=${GEMINI_API_KEY_SECRET:-$(metadata gemini-api-key-secret)}
IBKR_USERNAME_SECRET=${IBKR_USERNAME_SECRET:-$(metadata ibkr-username-secret)}
IBKR_PASSWORD_SECRET=${IBKR_PASSWORD_SECRET:-$(metadata ibkr-password-secret)}
IBKR_TRADING_MODE=${IBKR_TRADING_MODE:-$(metadata ibkr-trading-mode)}
IBKR_API_PORT=${IBKR_API_PORT:-$(metadata ibkr-api-port)}
IBKR_SOCKET_PORT=${IBKR_SOCKET_PORT:-$(metadata_optional ibkr-socket-port)}
IBKR_READ_ONLY_API=${IBKR_READ_ONLY_API:-$(metadata_optional ibkr-read-only-api)}
IBKR_TWOFA_DEVICE_SECRET=${IBKR_TWOFA_DEVICE_SECRET:-$(metadata_optional ibkr-twofa-device-secret)}
IBKR_VNC_PASSWORD_SECRET=${IBKR_VNC_PASSWORD_SECRET:-$(metadata_optional ibkr-vnc-password-secret)}
GEMINI_MODEL=${GEMINI_MODEL:-$(metadata_optional gemini-model)}
GEMINI_EMBEDDING_MODEL=${GEMINI_EMBEDDING_MODEL:-$(metadata_optional gemini-embedding-model)}
VERTEX_API_KEY_SECRET=${VERTEX_API_KEY_SECRET:-$(metadata_optional vertex-api-key-secret)}
VERTEX_MODEL=${VERTEX_MODEL:-$(metadata_optional vertex-model)}
TELEGRAM_BOT_TOKEN_SECRET=${TELEGRAM_BOT_TOKEN_SECRET:-$(metadata_optional telegram-bot-token-secret)}
TELEGRAM_CHAT_ID_SECRET=${TELEGRAM_CHAT_ID_SECRET:-$(metadata_optional telegram-chat-id-secret)}

mkdir -p /opt/riskdesk /opt/riskdesk/nginx /opt/riskdesk/postgres-init
mkdir -p /var/lib/riskdesk/ibkr-settings /var/log/riskdesk/ibkr
chown -R 1000:1000 /var/lib/riskdesk/ibkr-settings /var/log/riskdesk/ibkr

gcloud storage cp "gs://${CONFIG_BUCKET}/riskdesk/docker-compose.gce.yml" /opt/riskdesk/docker-compose.yml
gcloud storage cp "gs://${CONFIG_BUCKET}/riskdesk/render-secrets-env.sh" /opt/riskdesk/render-secrets-env.sh
gcloud storage cp "gs://${CONFIG_BUCKET}/riskdesk/backup-postgres-to-gcs.sh" /opt/riskdesk/backup-postgres-to-gcs.sh
gcloud storage cp "gs://${CONFIG_BUCKET}/riskdesk/nginx/local-prod.conf" /opt/riskdesk/nginx/local-prod.conf
gcloud storage cp "gs://${CONFIG_BUCKET}/riskdesk/postgres-init/01-extensions.sql" /opt/riskdesk/postgres-init/01-extensions.sql

chmod +x /opt/riskdesk/render-secrets-env.sh /opt/riskdesk/backup-postgres-to-gcs.sh

export PROJECT_ID
export DB_NAME
export DB_USERNAME_SECRET
export DB_PASSWORD_SECRET
export GEMINI_API_KEY_SECRET
export IBKR_USERNAME_SECRET
export IBKR_PASSWORD_SECRET
export IBKR_TRADING_MODE
export IBKR_API_PORT
export IBKR_SOCKET_PORT
export IBKR_READ_ONLY_API
export IBKR_TWOFA_DEVICE_SECRET
export IBKR_VNC_PASSWORD_SECRET
export GEMINI_MODEL
export GEMINI_EMBEDDING_MODEL
export VERTEX_API_KEY_SECRET
export VERTEX_MODEL
export TELEGRAM_BOT_TOKEN_SECRET
export TELEGRAM_CHAT_ID_SECRET

/opt/riskdesk/render-secrets-env.sh

cat > /opt/riskdesk/.env <<EOF
BACKEND_IMAGE=${BACKEND_IMAGE}
FRONTEND_IMAGE=${FRONTEND_IMAGE}
IBKR_GATEWAY_IMAGE=${IBKR_GATEWAY_IMAGE}
EOF

COMPOSE_CMD=${COMPOSE_CMD:-$(compose_cmd)}

cd /opt/riskdesk
${COMPOSE_CMD} pull
${COMPOSE_CMD} up -d --remove-orphans --force-recreate
