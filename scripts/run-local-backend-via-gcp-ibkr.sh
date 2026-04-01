#!/usr/bin/env bash

set -euo pipefail

PORT="${RISKDESK_IBKR_NATIVE_PORT:-4003}"
HOST="${RISKDESK_IBKR_NATIVE_HOST:-127.0.0.1}"
CLIENT_ID="${RISKDESK_IBKR_NATIVE_CLIENT_ID:-17}"

if ! nc -z "${HOST}" "${PORT}" >/dev/null 2>&1; then
  echo "IBKR GCP tunnel is not available on ${HOST}:${PORT}" >&2
  echo "Open it first with:" >&2
  echo "  gcloud compute ssh --tunnel-through-iap riskdesk-prod-a --zone us-central1-a -- -N -L ${PORT}:127.0.0.1:${PORT}" >&2
  exit 1
fi

export SPRING_PROFILES_ACTIVE=local
export RISKDESK_IBKR_NATIVE_HOST="${HOST}"
export RISKDESK_IBKR_NATIVE_PORT="${PORT}"
export RISKDESK_IBKR_NATIVE_CLIENT_ID="${CLIENT_ID}"

echo "Starting local backend against GCP IBKR Gateway on ${HOST}:${PORT} with clientId=${CLIENT_ID}"
exec mvn -q spring-boot:run "$@"
