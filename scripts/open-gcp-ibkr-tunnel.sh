#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE_NAME="${1:-local-ibkr-gcp}"
PROFILE_FILE="${ROOT_DIR}/scripts/profiles/${PROFILE_NAME}.env"

if [[ ! -f "${PROFILE_FILE}" ]]; then
  echo "Profile not found: ${PROFILE_FILE}" >&2
  exit 1
fi

set -a
source "${PROFILE_FILE}"
set +a

exec gcloud compute ssh \
  --tunnel-through-iap "${GCP_IAP_INSTANCE}" \
  --zone "${GCP_IAP_ZONE}" \
  -- -N -L "${RISKDESK_IBKR_NATIVE_PORT}:127.0.0.1:${RISKDESK_IBKR_NATIVE_PORT}"
