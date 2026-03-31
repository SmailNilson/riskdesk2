#!/usr/bin/env bash

set -euo pipefail

PROFILE_NAME="${1:-local-ibkr-gcp}"
RUNTIME_DIR="/tmp/riskdesk/${PROFILE_NAME}"

for service in backend frontend; do
  PID_FILE="${RUNTIME_DIR}/${service}.pid"
  if [[ -f "${PID_FILE}" ]]; then
    PID="$(cat "${PID_FILE}")"
    if kill -0 "${PID}" 2>/dev/null; then
      kill "${PID}" 2>/dev/null || true
    fi
    rm -f "${PID_FILE}"
  fi
done

echo "Profile ${PROFILE_NAME} stopped"
