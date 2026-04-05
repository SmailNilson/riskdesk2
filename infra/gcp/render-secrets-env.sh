#!/usr/bin/env bash

set -euo pipefail

require_env() {
  local name=$1
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}" >&2
    exit 1
  fi
}

read_secret() {
  local secret_name=$1
  gcloud secrets versions access latest \
    --project="${PROJECT_ID}" \
    --secret="${secret_name}"
}

maybe_read_secret() {
  local secret_name=${1:-}
  if [[ -z "${secret_name}" ]]; then
    return 0
  fi

  read_secret "${secret_name}"
}

require_env PROJECT_ID
require_env DB_NAME
require_env DB_USERNAME_SECRET
require_env DB_PASSWORD_SECRET
require_env GEMINI_API_KEY_SECRET
require_env IBKR_USERNAME_SECRET
require_env IBKR_PASSWORD_SECRET
require_env IBKR_TRADING_MODE

resolve_ibkr_socket_port() {
  if [[ -n "${IBKR_SOCKET_PORT:-}" ]]; then
    printf '%s' "${IBKR_SOCKET_PORT}"
    return
  fi

  case "${IBKR_TRADING_MODE}" in
    live)
      printf '4003'
      ;;
    paper)
      printf '4004'
      ;;
    *)
      echo "Unsupported IBKR_TRADING_MODE: ${IBKR_TRADING_MODE}" >&2
      exit 1
      ;;
  esac
}

mkdir -p /etc/riskdesk
chmod 700 /etc/riskdesk

{
  printf 'POSTGRES_DB=%s\n' "${DB_NAME}"
  printf 'POSTGRES_USER=%s\n' "$(read_secret "${DB_USERNAME_SECRET}")"
  printf 'POSTGRES_PASSWORD=%s\n' "$(read_secret "${DB_PASSWORD_SECRET}")"
} > /etc/riskdesk/postgres.env

{
  ibkr_socket_port=$(resolve_ibkr_socket_port)
  printf 'SERVER_PORT=8080\n'
  printf 'SERVER_FORWARD_HEADERS_STRATEGY=framework\n'
  printf 'SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/%s\n' "${DB_NAME}"
  printf 'SPRING_DATASOURCE_USERNAME=%s\n' "$(read_secret "${DB_USERNAME_SECRET}")"
  printf 'SPRING_DATASOURCE_PASSWORD=%s\n' "$(read_secret "${DB_PASSWORD_SECRET}")"
  printf 'SPRING_JPA_HIBERNATE_DDL_AUTO=%s\n' "${SPRING_JPA_HIBERNATE_DDL_AUTO:-update}"
  printf 'RISKDESK_IBKR_ENABLED=true\n'
  printf 'RISKDESK_IBKR_NATIVE_HOST=ibkr-gateway\n'
  printf 'RISKDESK_IBKR_NATIVE_PORT=%s\n' "${ibkr_socket_port}"
  printf 'RISKDESK_IBKR_NATIVE_CLIENT_ID=%s\n' "${IBKR_CLIENT_ID:-1}"
  printf 'RISKDESK_IBKR_NATIVE_READ_ONLY=%s\n' "${RISKDESK_IBKR_NATIVE_READ_ONLY:-true}"
  printf 'GEMINI_API_KEY=%s\n' "$(read_secret "${GEMINI_API_KEY_SECRET}")"
  printf 'GEMINI_MODEL=%s\n' "${GEMINI_MODEL:-gemini-3.1-pro-preview}"
  printf 'GEMINI_EMBEDDING_MODEL=%s\n' "${GEMINI_EMBEDDING_MODEL:-gemini-embedding-001}"

  if [[ -n "${TELEGRAM_BOT_TOKEN_SECRET:-}" && -n "${TELEGRAM_CHAT_ID_SECRET:-}" ]]; then
    printf 'RISKDESK_ALERTS_TELEGRAM_ENABLED=true\n'
    printf 'TELEGRAM_BOT_TOKEN=%s\n' "$(read_secret "${TELEGRAM_BOT_TOKEN_SECRET}")"
    printf 'TELEGRAM_CHAT_ID=%s\n' "$(read_secret "${TELEGRAM_CHAT_ID_SECRET}")"
  fi
} > /etc/riskdesk/runtime.env

{
  printf 'TWS_USERID=%s\n' "$(read_secret "${IBKR_USERNAME_SECRET}")"
  printf 'TWS_PASSWORD=%s\n' "$(read_secret "${IBKR_PASSWORD_SECRET}")"
  printf 'TRADING_MODE=%s\n' "${IBKR_TRADING_MODE}"
  printf 'READ_ONLY_API=%s\n' "${IBKR_READ_ONLY_API:-yes}"
  printf 'TWS_SETTINGS_PATH=/home/ibgateway/tws_settings\n'
  printf 'TIME_ZONE=%s\n' "${IBKR_TIME_ZONE:-America/New_York}"
  printf 'JAVA_HEAP_SIZE=%s\n' "${IBKR_JAVA_HEAP_SIZE:-768}"
  printf 'AUTO_RESTART_TIME=%s\n' "${IBKR_AUTO_RESTART_TIME:-23:45}"
  printf 'RELOGIN_AFTER_TWOFA_TIMEOUT=%s\n' "${IBKR_RELOGIN_AFTER_TWOFA_TIMEOUT:-yes}"

  if [[ -n "${IBKR_TWOFA_DEVICE_SECRET:-}" ]]; then
    printf 'TWOFA_DEVICE=%s\n' "$(maybe_read_secret "${IBKR_TWOFA_DEVICE_SECRET}")"
  fi

  if [[ -n "${IBKR_VNC_PASSWORD_SECRET:-}" ]]; then
    printf 'VNC_SERVER_PASSWORD=%s\n' "$(maybe_read_secret "${IBKR_VNC_PASSWORD_SECRET}")"
  fi
} > /etc/riskdesk/ibkr.env

chmod 600 /etc/riskdesk/postgres.env /etc/riskdesk/runtime.env /etc/riskdesk/ibkr.env
