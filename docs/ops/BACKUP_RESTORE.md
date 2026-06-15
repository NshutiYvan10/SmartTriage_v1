# Backup & Restore Runbook

The PostgreSQL database is the **single source of truth** for SmartTriage —
patients, visits, triage records, vitals, medication orders/doses, alerts, the
full audit trail. The application is stateless. **If the database is lost and
there is no backup, the clinical record is gone.** Treat backups as a
patient-safety control, not an IT nicety.

> `«FILL IN»` = set for your environment.
> Assumptions: PostgreSQL 14, database name `«DB_NAME»`, host `«DB_HOST»`,
> backup user `«BACKUP_USER»` (a read-capable role). Schema is managed by
> Flyway — do not back up "the schema" separately; the data dump + the app's
> migrations reconstruct everything.

## 1. What to back up

- **The whole database** (`pg_dump` of `«DB_NAME»`). One logical dump captures
  schema + data + the `flyway_schema_history` table (so a restore lands on a
  known migration version).
- Nothing else is required for recovery (the app jar/image is rebuilt from
  source; config comes from env/secret store). Optionally archive the deployed
  **commit SHA** alongside each backup so you can rebuild the exact app.

## 2. Nightly logical backup (baseline)

Run on the DB host (or a host that can reach it). Schedule via cron/systemd
timer at a low-traffic hour (EDs are quieter ~03:00 local).

```bash
# /usr/local/bin/smarttriage-backup.sh
set -euo pipefail
STAMP=$(date +%Y%m%d-%H%M%S)
DEST="«BACKUP_DIR»"                       # e.g. /var/backups/smarttriage
mkdir -p "$DEST"
export PGPASSWORD="«BACKUP_PASSWORD»"     # prefer ~/.pgpass over env
pg_dump -h «DB_HOST» -U «BACKUP_USER» -d «DB_NAME» \
        --format=custom --compress=6 \
        --file="$DEST/smarttriage-$STAMP.dump"
# Record the running app version next to the dump (optional but recommended)
echo "$STAMP commit=«DEPLOYED_SHA»" >> "$DEST/MANIFEST.txt"
# Prune older than retention
find "$DEST" -name 'smarttriage-*.dump' -mtime +«RETENTION_DAYS» -delete
```

Cron example (03:10 daily):

```cron
10 3 * * *  /usr/local/bin/smarttriage-backup.sh >> /var/log/smarttriage-backup.log 2>&1
```

**Retention**: keep ≥ `«RETENTION_DAYS»` (suggest 14–30 daily + a monthly kept
12 months for medico-legal needs — confirm with hospital records policy).

**Off-host copy (REQUIRED)**: a backup on the same disk as the database does not
survive a disk failure. Copy each dump to a second location (another host,
NAS, or object storage) — `«OFFSITE_TARGET»`. Encrypt in transit and at rest;
this is patient data.

## 3. Point-in-time recovery (optional, stronger)

Nightly dumps mean you can lose up to ~24h on a total loss. If that is
unacceptable, enable continuous archiving (WAL archiving / `pg_basebackup` +
`archive_command`) or use your managed-Postgres provider's PITR. Decide the
acceptable **RPO** (data-loss window) and **RTO** (time-to-restore) with the
hospital and size the strategy to it. `«RPO»` / `«RTO»` = FILL IN.

## 4. Restore procedure

> Practice this BEFORE go-live (step 5). A backup you have never restored is a
> hope, not a backup.

1. **Stop the app** (so nothing writes mid-restore).
2. Provision a clean, empty database (or a fresh target):
   ```bash
   createdb -h «DB_HOST» -U «ADMIN_USER» «DB_NAME»_restore
   ```
3. Restore the dump:
   ```bash
   pg_restore -h «DB_HOST» -U «ADMIN_USER» -d «DB_NAME»_restore \
              --no-owner --jobs=4 «DEST»/smarttriage-«STAMP».dump
   ```
4. **Point the app at the restored DB** (`SPRING_DATASOURCE_URL`) and start it.
   On boot Flyway runs `validate`/`migrate`: because the dump included
   `flyway_schema_history`, it should report the schema already at the dump's
   version and apply only newer migrations (if you restored an older dump into
   a newer app). Watch the logs for `Successfully validated`/`Migrating schema`.
5. **Verify** (step 6) before letting clinicians back in.

## 5. Pre-go-live: rehearse a restore

Once, before the first patient, restore the latest dump into a throwaway
database, boot the app against it, log in, and confirm a known patient/visit
appears. Record how long it took — that is your real RTO. Re-rehearse after any
major infra change.

## 6. Post-restore verification checklist

- [ ] App boots; `/actuator/health` is `UP`.
- [ ] Flyway log shows the expected migration version (≥ V71), no failures.
- [ ] Spot-check row counts vs. expectation: `patients`, `visits`,
      `triage_records`, `vital_signs`, `medication_administrations`,
      `medication_doses`, `clinical_alerts`.
- [ ] A recent known visit opens in the UI with its triage + vitals + meds.
- [ ] Audit trail rows present (the system must remain medico-legally complete).
- [ ] No `flyway_schema_history` rows with `success = false`.

## 7. Backup health is itself monitored

A silently-failing backup is the classic disaster. Alert if:
- the nightly job exits non-zero, **or**
- the newest dump in `«BACKUP_DIR»` (and the offsite copy) is older than 26h.

See MONITORING.md.
