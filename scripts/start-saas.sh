#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE_NAME="${1:-local-ibkr-gcp}"
PROFILE_FILE="${ROOT_DIR}/scripts/profiles/${PROFILE_NAME}.env"
RUNTIME_DIR="/tmp/riskdesk/${PROFILE_NAME}"

if [[ ! -f "${PROFILE_FILE}" ]]; then
  echo "Profile not found: ${PROFILE_FILE}" >&2
  exit 1
fi

set -a
source "${PROFILE_FILE}"
set +a

if ! nc -z "${RISKDESK_IBKR_NATIVE_HOST}" "${RISKDESK_IBKR_NATIVE_PORT}" >/dev/null 2>&1; then
  echo "IBKR tunnel is not available on ${RISKDESK_IBKR_NATIVE_HOST}:${RISKDESK_IBKR_NATIVE_PORT}" >&2
  echo "Open it first with:" >&2
  echo "  ${ROOT_DIR}/scripts/open-gcp-ibkr-tunnel.sh ${PROFILE_NAME}" >&2
  exit 1
fi

mkdir -p "${RUNTIME_DIR}"

BACKEND_PID_FILE="${RUNTIME_DIR}/backend.pid"
FRONTEND_PID_FILE="${RUNTIME_DIR}/frontend.pid"
BACKEND_LOG="${RUNTIME_DIR}/backend.log"
FRONTEND_LOG="${RUNTIME_DIR}/frontend.log"

if [[ -f "${BACKEND_PID_FILE}" ]] && kill -0 "$(cat "${BACKEND_PID_FILE}")" 2>/dev/null; then
  echo "Backend already running for profile ${PROFILE_NAME}" >&2
else
  (
    cd "${ROOT_DIR}"
    nohup ./scripts/run-local-backend-via-gcp-ibkr.sh >"${BACKEND_LOG}" 2>&1 &
    echo $! > "${BACKEND_PID_FILE}"
  )
fi

if [[ -f "${FRONTEND_PID_FILE}" ]] && kill -0 "$(cat "${FRONTEND_PID_FILE}")" 2>/dev/null; then
  echo "Frontend already running for profile ${PROFILE_NAME}" >&2
else
  (
    cd "${ROOT_DIR}/frontend"
    nohup env \
      NEXT_PUBLIC_API_URL="${NEXT_PUBLIC_API_URL}" \
      NEXT_PUBLIC_WS_URL="${NEXT_PUBLIC_WS_URL}" \
      PORT="${FRONTEND_PORT}" \
      npm run dev >"${FRONTEND_LOG}" 2>&1 &
    echo $! > "${FRONTEND_PID_FILE}"
  )
fi

echo "Profile ${PROFILE_NAME} started"
echo "Backend log:  ${BACKEND_LOG}"
echo "Frontend log: ${FRONTEND_LOG}"
