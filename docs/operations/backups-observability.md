# Backups, restore and observability

## PostgreSQL backups

The scripts in `scripts/backup` create custom-format PostgreSQL dumps, validate the archive list, write SHA-256 manifests and apply a retention window. Production runs require age encryption:

```bash
export APP_ENV=production
export DATABASE_URL='postgresql://backup_user:...@db-host:5432/imobpro'
export BACKUP_DIR=/secure/backups/imobpro
export AGE_RECIPIENT='age1...'
export RETENTION_DAYS=30
bash scripts/backup/backup_postgres.sh
```

Schedule the command with the infrastructure scheduler (cron, Kubernetes CronJob, Railway job, etc.). Use a dedicated least-privilege backup role and store the age private identity separately from the backup destination. Keep an off-site copy with immutable retention. The script only verifies the dump can be listed; operators must perform recurring restore drills.

## Restore drill

Restore only into a new, isolated staging/restore database. The script refuses other target labels:

```bash
export BACKUP_FILE=/secure/backups/imobpro-20261106T020000Z.dump.age
export AGE_IDENTITY_FILE=/secure/keys/backup-identity.txt
export RESTORE_DATABASE_URL='postgresql://restore_user:...@db-host:5432/imobpro_restore'
export RESTORE_TARGET_LABEL=restore
export CONFIRM_RESTORE=YES
bash scripts/backup/restore_postgres.sh
```

After restore, run Flyway validation, API integration tests, row-count checks, and a sample of tenant-isolation checks before accepting the drill. Never point the restore script at the live production database.

## Observability

- Spring Boot Actuator exposes readiness/liveness health groups and Prometheus metrics.
- The API readiness probe checks PostgreSQL; Docker Compose waits for readiness before starting the BFF.
- Keep `/actuator/prometheus` private to the metrics network and use an authenticated scrape identity. Do not publish metrics publicly.
- Collect API/BFF logs centrally, redact authorization/cookie headers, passwords, refresh tokens, personal data and uploaded content.
- Alert on readiness failures, 5xx rate, refresh failures, database saturation, outbox backlog/age, failed automation events, storage errors and backup age.
- Establish an explicit SLO, retention policy, restore RPO/RTO and incident owner before production.

## Production checklist

- Rotate all Compose development defaults; use secret manager injection.
- Enforce HTTPS, strict host/origin allowlists, firewall database/Redis/object storage, and least-privilege service credentials.
- Configure S3 versioning/encryption/lifecycle and object-access policy; pre-create the bucket.
- Run antivirus/content scanning and image re-encoding in a controlled worker before serving untrusted uploads. This repository currently validates file signatures and size but does not yet provide malware scanning or image re-encoding.
- Use external rate-limit storage when running multiple BFF replicas; the current BFF limiter is process-local and therefore not sufficient as a cluster-wide production control.
- Perform a penetration test and LGPD review before cutover.
