# SmartTriage — Operations Readiness

Operational runbooks for running SmartTriage in a real hospital. This is a
**life-critical** system: when it is down or silently mis-behaving, patients
wait longer and deterioration can be missed. These docs exist so the people
running it know how to keep it safe.

> Values written like `«FILL IN»` are deployment-specific — set them for your
> environment before go-live. SmartTriage's committed config is for local/dev;
> production values come from environment variables / your secret store.

## Contents

| Runbook | Purpose |
|---|---|
| [BACKUP_RESTORE.md](BACKUP_RESTORE.md) | How patient data is backed up and how to restore it. The PostgreSQL database is the single source of truth. |
| [MONITORING.md](MONITORING.md) | What to watch (app health, DB, the scheduled clinical-safety monitors) and the alert thresholds. |
| [DOWNTIME_PROCEDURE.md](DOWNTIME_PROCEDURE.md) | The clinical paper-fallback the ED uses when SmartTriage is unavailable, and how to reconcile afterwards. |

## System shape (what an operator needs to know)

- **App**: Spring Boot 4 (Java 21), served on port `8080`. Stateless except for
  the database — restarting the app loses nothing; **all durable state is in
  PostgreSQL**.
- **Database**: PostgreSQL 14. Schema is owned by **Flyway** migrations
  (`V1 … V71` at time of writing) applied automatically on boot. Never hand-edit
  the schema — add a migration.
- **Real-time**: STOMP over WebSocket at `/ws/smarttriage`, using an **in-memory
  broker**. This means the app is effectively **single-instance** for real-time
  correctness today — running two instances behind a load balancer would split
  the broker and drop cross-instance alerts. Multi-instance needs an external
  broker (RabbitMQ/Redis) first (noted in `WebSocketConfig`).
- **Background safety jobs**: ~17 `@Scheduled` monitors run inside the app
  process (missed-dose escalation, sepsis bundle, deterioration, waiting-time,
  re-triage, identity-overdue, device heartbeat, …). **If the app is up but these
  stall, safety alerts silently stop firing** — see MONITORING.md.

## Go-live readiness checklist

Do **not** put a real patient on this until every box is checked.

- [ ] **Secrets rotated (S9 — hard blocker).** The repo currently contains a
      committed SMTP password and a default JWT secret. Both MUST be replaced
      with strong values supplied via environment variables / secret store, and
      the old ones treated as compromised. A default JWT secret means anyone can
      forge a login token. *Owner: project lead.*
- [ ] **Datasource via env**, not committed properties: `SPRING_DATASOURCE_URL`,
      `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` → «FILL IN».
- [ ] **`spring.profiles.active`** set to a production profile (the committed
      default is `dev`, which enables the vitals **simulator** — see below).
- [ ] **Vitals simulator OFF.** `VitalSimulatorService` is a dev aid that
      fabricates device readings; confirm it does not run in production
      (gate by profile / config). Fake vitals in a live ED are dangerous.
- [ ] **CI is green** on the deployed commit (`mvn verify` incl. the
      Testcontainers integration suite, + frontend build + tsc ratchet).
- [ ] **Backups configured and a test restore performed** (BACKUP_RESTORE.md).
- [ ] **Monitoring + on-call alerting wired** (MONITORING.md), including a
      scheduled-job liveness check.
- [ ] **Downtime SOP printed and at the triage desk** (DOWNTIME_PROCEDURE.md).
- [ ] **Single app instance** (until an external STOMP broker is configured).
- [ ] **TLS terminates in front of the app**; `/actuator/**` not exposed to the
      public internet.
- [ ] **Clinical UAT signed off** by the receiving hospital's ED lead.

## Escalation

When something is wrong in production, the order of questions is:
1. Is the **app** up? (`/actuator/health`)
2. Is the **database** reachable and not full? (disk!)
3. Are the **scheduled monitors** still ticking? (logs / metrics)
4. Is the **WebSocket** delivering? (clients reconnecting?)

Details and thresholds in MONITORING.md.
