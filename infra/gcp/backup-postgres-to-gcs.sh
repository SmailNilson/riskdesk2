#!/usr/bin/env bash

set -euo pipefail

source /etc/riskdesk/backup.env
source /etc/riskdesk/postgres.env

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
archive="/tmp/${POSTGRES_DB}-${timestamp}.sql.gz"

docker exec riskdesk-postgres pg_dump -U "${POSTGRES_USER}" "${POSTGRES_DB}" | gzip -9 > "${archive}"
gcloud storage cp "${archive}" "gs://${BACKUP_BUCKET}/postgres/${POSTGRES_DB}-${timestamp}.sql.gz"
rm -f "${archive}"
