#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${BACKUP_FILE:?BACKUP_FILE must point to a verified backup}"
: "${RESTORE_DATABASE_URL:?RESTORE_DATABASE_URL must point to an isolated restore database}"
: "${RESTORE_TARGET_LABEL:?Set RESTORE_TARGET_LABEL to staging or restore}"
: "${CONFIRM_RESTORE:?Set CONFIRM_RESTORE=YES after reviewing the target}"

if [[ "${CONFIRM_RESTORE}" != "YES" ]]; then
  echo "Restore cancelled: explicit CONFIRM_RESTORE=YES is required." >&2
  exit 2
fi
if [[ "${RESTORE_TARGET_LABEL}" != "staging" && "${RESTORE_TARGET_LABEL}" != "restore" ]]; then
  echo "Refusing restore outside an explicitly isolated staging/restore target." >&2
  exit 2
fi
if [[ ! -f "${BACKUP_FILE}" ]]; then
  echo "Backup file not found." >&2
  exit 2
fi

work_file="${BACKUP_FILE}"
temporary=""
cleanup() { [[ -z "${temporary}" ]] || rm -f "${temporary}"; }
trap cleanup EXIT

if [[ "${BACKUP_FILE}" == *.age ]]; then
  : "${AGE_IDENTITY_FILE:?AGE_IDENTITY_FILE is required for encrypted backups}"
  command -v age >/dev/null || { echo "age is required." >&2; exit 2; }
  temporary="$(mktemp)"
  age --decrypt --identity "${AGE_IDENTITY_FILE}" --output "${temporary}" "${BACKUP_FILE}"
  work_file="${temporary}"
fi

pg_restore --list "${work_file}" >/dev/null
pg_restore --dbname="${RESTORE_DATABASE_URL}" --no-owner --no-acl --exit-on-error "${work_file}"
echo '{"status":"restore_completed","target_label":"'"\${RESTORE_TARGET_LABEL}"'"}'
