# ED Downtime Procedure (paper fallback)

When SmartTriage is unavailable, the Emergency Department **does not stop** —
it falls back to paper, then reconciles into the system on recovery. This SOP
keeps triage safe during the gap and keeps the record medico-legally complete
afterwards. Print it and keep a copy at the triage desk and the charge-nurse
station.

> This is a clinical SOP — adapt wording with the receiving hospital's ED lead.
> The technical recovery side lives in [BACKUP_RESTORE.md](BACKUP_RESTORE.md) /
> [MONITORING.md](MONITORING.md).

## 1. When to invoke

Invoke downtime mode when SmartTriage is unusable for triage/charting:
- the app or dashboards won't load, or
- login fails for everyone, or
- the system is up but clearly wrong (stale data, vitals not updating).

**The charge nurse on duty declares downtime** and announces it to the ED. Note
the **time declared** on the downtime log. Notify IT/on-call in parallel
(MONITORING.md escalation), but **do not wait for IT to start paper**.

## 2. What still works vs. what stops

During downtime, assume **all automated safety nets are OFF**:
- ❌ No automatic missed-dose / STAT-med overdue escalation.
- ❌ No sepsis 1-hour bundle timer, no deterioration / waiting-time / re-triage alerts.
- ❌ No IoT monitor-disconnect alerts (a dropped monitor will NOT warn you).
- ❌ No incoming-ambulance pre-arrival alert.

→ **The team must track these manually.** Assign the charge nurse to hold the
master downtime log and watch the clock on time-critical tasks (drug rounds,
sepsis bundle, reassessment intervals) that the system normally nags about.

## 3. Paper packs (prepare in advance — keep stocked)

Keep a sealed "ED Downtime Kit" with printed forms:
- **Rwanda National Triage forms** — Adult (>12y) and Child (3–12y) — the same
  TEWS criteria the system uses, so paper triage stays consistent with digital.
- **Vitals/observation chart** and **drug/medication chart**.
- **Downtime patient log** (master sheet): running number, time, name (or
  "Unknown" + descriptor), triage category, location/zone, brief complaint.
- **Ambulance pre-arrival note** pad (mechanism, field triage, ETA, vitals).
- Pens, clipboards, this SOP.

## 4. During downtime — triage & care

1. **Register on the downtime log.** Give each patient a sequential downtime
   number (e.g. `DT-001`). For an unidentified patient, use a clear placeholder
   ("Unknown male, ~40, RTA") — this maps to the system's NATO-phonetic
   "Unknown Alpha" placeholder at reconciliation.
2. **Triage on the paper Rwanda form** exactly as normal — record vitals, TEWS,
   emergency/very-urgent/urgent signs, and the resulting category
   (RED/ORANGE/YELLOW/GREEN). Place the patient by category as usual (RED →
   resus). The paper category is authoritative until re-entered.
3. **Chart meds on the paper drug chart** — drug, dose, route, time, given-by,
   and **manually track the next-due time** (no system reminder). Keep allergy
   checks manual and explicit.
4. **Vitals** go on the paper obs chart at the normal cadence; if a monitor is
   connected, read it directly — do not assume it is being recorded anywhere.
5. **Ambulance inbound**: take the pre-arrival note by phone/radio onto the
   ambulance pad; prep the bay manually for RED/critical.
6. **Handover** between shifts uses the paper logs — hand over the master
   downtime log and any open drug charts explicitly.

## 5. On recovery — reconciliation (do NOT skip)

When IT confirms SmartTriage is back (and a restore, if any, is verified —
BACKUP_RESTORE.md §6):

1. **Announce downtime ended**; note the **time restored** on the master log.
2. **Back-enter every downtime patient** into SmartTriage, in order, using the
   paper forms — register the patient/visit, enter the triage (with the
   on-paper vitals and category), and the meds given (with their real
   administration times). Preserve the original clinical times, not the
   data-entry time.
3. **Resolve placeholders**: for "Unknown" patients now identified, use the
   chart's **Set Patient Identity** action to attach the real identity (this
   preserves the record and audit trail).
4. **Reconcile drug charts**: confirm what was given on paper matches what's now
   recorded; re-establish next-due times so the system's dose monitor resumes
   correctly from the right baseline.
5. **Re-triage if needed**: for any patient whose acuity is uncertain after the
   gap, perform a fresh triage in-system so the live boards are accurate.
6. **Verify counts**: number of patients in the system for the downtime window
   matches the master log. File the paper pack with the date/time window as the
   downtime record (medico-legal).

## 6. After-action

Within a week, the ED lead + IT review: cause, duration, what the paper fallback
caught/missed, and any restock or SOP fix. Record it. Repeated downtime of the
same cause is a backlog item, not a recurring fire drill.
