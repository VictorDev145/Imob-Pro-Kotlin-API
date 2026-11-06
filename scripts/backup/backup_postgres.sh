#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${DATABASE_URL:?DATABASE_URL must point to the PostgreSQL database}"
: "${BACKUP_DIR:?BACKUP_DIR must be configured}"
: "${RETENTION_DAYS:=14}"
: "${APP_ENV:=development}"

if [[ "${APP_ENV}" == "production" && -z "${AGE_RECIPIENT:-}" ]]; then
  echo "AGE_RECIPIENT is required for production backups." >&2
  exit 2
fi

mkdir -p "${BACKUP_DIR}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="imobpro-${timestamp}"
dump="${BACKUP_DIR}/${base}.dump"
manifest="${BACKUP_DIR}/${base}.sha256"

pg_dump --dbname="${DATABASE_URL}" --format=custom --no-owner --no-acl --file="${dump}"
pg_restore --list "${dump}" >/dev/null
sha256sum "${dump}" > "${manifest}"

if [[ -n "${AGE_RECIPIENT:-}" ]]; then
  command -v age >/dev/null || { echo "age is required when AGE_RECIPIENT is set." >&2; exit 2; }
  age --recipient "${AGE_RECIPIENT}" --output "${dump}.age" "${dump}"
  rm -f "${dump}"
  sha256sum "${dump}.age" > "${manifest}"
fi

find "${BACKUP_DIR}" -type f -mtime "+${RETENTION_DAYS}" -delete
printf '{"status":"backup_created","file":"%s","manifest":"%s"}\n' "$(basename "${dump}${AGE_RECIPIENT:+.age}")" "$(basename "${manifest}")"
