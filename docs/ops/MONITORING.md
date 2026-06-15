# Monitoring Runbook

The dangerous failure mode for SmartTriage is **not** a crash — a crash is
obvious and triggers the downtime SOP. The dangerous mode is the app **up but
silently not doing its safety job**: the database disk fills and writes fail, or
a background monitor stalls and missed-dose / sepsis / deterioration alerts stop
firing while the dashboard still looks normal. Monitoring exists to catch that.

> `«FILL IN»` = wire to your monitoring/alerting stack (Prometheus+Alertmanager,
> Grafana, a hosted uptime checker, or even a cron + email — anything that pages
> a human). The app exposes Spring Boot Actuator; use it.

## Endpoints (Actuator)

On port `8080` (committed config — `management.endpoints.web.exposure.include=health,info,metrics`):

| Endpoint | Use |
|---|---|
| `GET /actuator/health` | Liveness + readiness. Includes a `db` component (DB connectivity) and `diskSpace`. `show-details` is `when-authorized`, so an authenticated/internal scrape sees component detail; anonymous sees only `UP`/`DOWN`. |
| `GET /actuator/info` | Build/version info. |
| `GET /actuator/metrics` | Micrometer metrics (JVM, HTTP, datasource pool, etc.). |

**Do not expose `/actuator/**` to the public internet** — scrape it from inside
the deployment network only.

## Tier-1 alerts (page immediately)

| Signal | How to check | Threshold → action |
|---|---|---|
| **App down** | `/actuator/health` not `UP` (or no HTTP response) | down > 1–2 min → page. Run the [downtime SOP](DOWNTIME_PROCEDURE.md). |
| **Database unreachable** | `health` `db` component `DOWN` | any → page. Without the DB nothing is recorded. |
| **DB disk near full** | DB host disk usage; `health` `diskSpace` on the app host | ≥ 85% → warn, ≥ 95% → page. A full DB disk = all writes fail = clinical stop. This is the most common silent killer — watch it. |
| **Scheduled safety monitors stalled** | see "Scheduled jobs" below | no monitor activity for > 5 min → page. Means safety alerts have silently stopped. |

## Tier-2 alerts (notify, investigate same shift)

- **Error-log spike** — a jump in `ERROR` lines, especially repeated authz
  fail-closed errors (`canAssign/canRevokeDelegation/... evaluation error`) or
  scheduler exceptions, signals a real problem even if health is `UP`.
- **Datasource pool exhaustion** — `metrics` HikariCP active==max sustained →
  requests will start timing out.
- **WebSocket churn** — clients repeatedly reconnecting (server logs
  `[WS] Disconnected` / SockJS reconnects) means real-time alerts may not be
  reaching dashboards even though REST works.
- **Backup stale** — newest dump (and offsite copy) older than 26h
  ([BACKUP_RESTORE.md](BACKUP_RESTORE.md)).

## Scheduled jobs — the safety heartbeat

SmartTriage runs its clinical-safety automation as `@Scheduled` jobs **inside
the app process**. If these stop, the UI keeps working but the system stops
*catching things*. Monitor that they keep ticking.

Production scheduled monitors (and why their silence is dangerous):

| Monitor | Guards (silence = …) |
|---|---|
| `MedicationDoseMonitorService` (60s) | overdue/missed dose escalation — **missed meds go unflagged** |
| `MedicationStatMonitorService` | STAT/urgent med overdue escalation |
| `SepsisBundleMonitorService` | 1-hour sepsis bundle timer — **sepsis bundle lapses unnoticed** |
| `AlertEscalationService` | un-acked critical-alert re-paging (Tier 2/3 + audible) — **critical alerts never escalate** |
| `WaitingTimeMonitorService` | SATS max-wait breaches per triage category |
| `ReassessmentSchedulerService` | reassessment-due alerts |
| `RetriageBackfillScheduler` / `EmsRetriageMonitor` (60s) | re-triage due / paramedic field-triage not reviewed |
| `DeviceHeartbeatScheduler` (15s) + `MonitoringStateWatcher` | IoT monitor disconnects — **a dropped monitor looks like stable vitals** |
| `IdentityOverdueScheduler` (5 min) | unidentified-patient identity overdue (2h RESUS alert) |
| `IcuAutoDetectionService` | ICU-escalation auto-detection |
| `LabTurnaroundMonitorService` | critical-lab turnaround / unacknowledged criticals |
| `HandoverReportScheduler` | shift-handover report generation |
| `ShiftMaterializerScheduler` | materialises rosters at shift boundaries |
| `StalePendingRequestScheduler` | stale swap/leave requests |
| `QualityMetricsScheduler` | quality-metric rollups (not safety-critical) |

> Note: `VitalSimulatorService` is a **dev-only** simulator — it must NOT run in
> production (gate by profile). See the go-live checklist.

**How to detect a stall:** each monitor logs on its tick (e.g. `[ems]`,
`[identity-overdue]`, dose-monitor sweeps). The simplest reliable check:
alert if the log shows **no scheduled-monitor activity at all for > 5 minutes**
(the 15s/60s jobs guarantee frequent lines in healthy operation). 

**Known risk to watch:** Spring's default `@Scheduled` runs on a **single
thread**. One long-running or wedged job blocks *all* the others — so a stall
tends to be all-or-nothing. Mitigations: keep scheduled methods fast (they
mostly do short queries), and/or configure a dedicated scheduler pool. If you
add a custom Actuator `HealthIndicator` that records each monitor's last-run
timestamp, you get this Tier-1 signal directly in `/actuator/health` — a
recommended hardening follow-up.

## Logs

- Centralise app logs (`«LOG_SINK»`) and retain ≥ `«LOG_RETENTION»` (align with
  medico-legal record policy — this includes the clinical audit narrative).
- Key signals to index: `ERROR`, `WARN` from the `*Monitor*`/`*Scheduler*`
  classes, `AlertEscalationService`, and the authz `evaluation error` lines.

## Minimal viable setup (if you have nothing else)

1. An external uptime check hitting `/actuator/health` every minute → page on
   non-`UP`.
2. A disk-space alert on the DB host at 85/95%.
3. A 5-minute "no scheduled-monitor log line" alert.
4. The backup-freshness alert from BACKUP_RESTORE.md.

That four-alarm set covers the failure modes that actually hurt patients.
