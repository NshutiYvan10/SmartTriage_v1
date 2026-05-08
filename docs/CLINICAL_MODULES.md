# SmartTriage — Clinical Decision Modules

A guide to every clinical-safety module in SmartTriage: what it does, when it fires, what the system does next, and how to test it end-to-end. Written for clinicians, QI leads, and engineers who need to understand the system without reading the source.

> **A note on testing.** Every "How to test" section assumes you can sign in as the relevant role and call the API directly (Postman / curl) or use the matching UI page. Threshold values, scheduler intervals, and alert types in this document are quoted from the actual source — not paraphrased.

## Table of contents

1. [Sepsis Detection Engine](#1-sepsis-detection-engine)
2. [Stroke / MI Fast-Track](#2-stroke--mi-fast-track)
3. [Hypoglycemia Enforcement](#3-hypoglycemia-enforcement)
4. [Infection Isolation & Public Health](#4-infection-isolation--public-health)
5. [Dynamic Re-triage](#5-dynamic-re-triage)
6. [ICU Escalation](#6-icu-escalation)
7. [Direct Resus Admission (RED bypass)](#7-direct-resus-admission-red-bypass)
8. [Lab Critical-Value Engine](#8-lab-critical-value-engine)
9. [Lab Two-Step Verification](#9-lab-two-step-verification)
10. [EMS Pre-Arrival & 15-min Re-triage Clock](#10-ems-pre-arrival--15-min-re-triage-clock)
11. [Pediatric Safety Branching](#11-pediatric-safety-branching)
12. [Medication Safety Engine](#12-medication-safety-engine)
13. [Clinical Pathways](#13-clinical-pathways)
14. [Background schedulers — quick reference](#background-schedulers--quick-reference)

---

## 1. Sepsis Detection Engine

### What it does

Screens every patient for sepsis using the two standard scoring systems (qSOFA and SIRS) and tracks the **1-hour sepsis bundle**. Sepsis kills the fastest of any ED presentation we treat — every hour without antibiotics raises mortality by ~7% in early septic shock. The engine flags suspicion early so the clinical team starts antibiotics, fluids, and lactate within an hour.

### How it works

Two parallel scores are computed from a triage's vitals + AVPU:

- **qSOFA** — points: `AVPU != ALERT` OR `GCS < 15` (+1), `RR ≥ 22` (+1), `SBP ≤ 100` (+1). Score ≥ 2 → **SEPSIS_SUSPECTED**.
- **SIRS** — points: `temperature > 38.0 °C` OR `< 36.0 °C` (+1), `HR > 90` (+1), `RR > 20` (+1). Score ≥ 2 → **SIRS_POSITIVE**.

Organ-dysfunction escalation:
- `SBP < 90` → **SEVERE_SEPSIS**
- `SBP < 70` while SEVERE → **SEPTIC_SHOCK**

### What triggers it

Any of: a triage form being filed; a vitals row being recorded; an explicit `POST /sepsis/screen/{visitId}` call.

### What happens

- A `ClinicalAlert` is created with `AlertType.SEPSIS_SCREENING`.
- A `SepsisScreening` record is persisted (visit-scoped) so the bundle clock can start.
- The 1-hour bundle scheduler watches the screening:
  - **Bundle-not-started > 15 min** → CRITICAL "SEPSIS BUNDLE NOT STARTED" alert.
  - **Bundle in progress > 60 min** → CRITICAL "SEPSIS BUNDLE OVERDUE" alert.
- Frontend: `modules/sepsis/SepsisDashboard.tsx` lists every active screening, the bundle clock, and the to-do tasks (lactate, blood cultures, broad-spectrum antibiotics, 30 mL/kg crystalloid).

### How to test

1. Sign in as a doctor or nurse.
2. Find or create a visit. File a triage form with: `RR=22`, `SBP=100`, `AVPU=VOICE` (any one of these makes qSOFA ≥ 2).
3. Verify a `SEPSIS_SCREENING` alert appears on the visit. Check `SepsisDashboard` — the patient is listed with a bundle clock.
4. Don't start the bundle. Wait > 15 minutes (the `SepsisBundleMonitorService` ticks every 60 s).
5. Confirm a CRITICAL "SEPSIS BUNDLE NOT STARTED" alert fires.
6. To exercise the overdue path: start the bundle, then leave it incomplete for > 60 min → CRITICAL "SEPSIS BUNDLE OVERDUE".

---

## 2. Stroke / MI Fast-Track

### What it does

Detects suspected stroke and ST-elevation MI (STEMI) so the patient bypasses standard triage and goes straight to imaging / cath lab. Stroke has a 4.5-hour tPA window; STEMI has a 90-minute door-to-balloon target. Missing either window kills brain tissue or cardiac muscle that doesn't grow back.

### How it works

Two detection rules running on the most recent triage:

**Stroke**
- Indicators (each adds points): `vuFocalNeurologicDeficit` (+2), `vuAlteredMentalStatus`, `AVPU != ALERT`, `hasComa`, `hasConvulsions`.
- Chief-complaint keywords also add: "facial droop", "arm/leg weakness", "hemiparesis", "hemiplegia", "speech difficulty", "slurred", "aphasia", "dysphasia", "sudden onset", "vision loss", "double vision".
- Score `≥ 3` OR a focal neuro deficit → **STROKE_SUSPECTED** (confidence ≥ 0.7).
- Otherwise with some indicators → **TIA_SUSPECTED**.

**STEMI** (must have at least the chest-pain signal to fire)
- `vuChestPain` (+2), `vuShortnessOfBreath`, age `> 40`, chronic conditions containing "diabetes" / "hypertension" / "cardiac".
- Chief-complaint keywords: chest pain/tightness/pressure, radiating, jaw, left arm, diaphoresis, sweating, nausea.
- Threshold met → **STEMI_SUSPECTED**.

### What triggers it

A triage form is filed, or an explicit `POST /fasttrack/activate` call references the visit.

### What happens

- A `ClinicalAlert` of type `VITAL_SIGN_ABNORMAL` is fired (severity HIGH).
- A `FastTrackActivation` is persisted, listing the protocol (`STROKE_PROTOCOL` or `STEMI_PROTOCOL`).
- Frontend `modules/fasttrack/FastTrackDashboard.tsx` shows a live worklist with the door-clock countdown for each patient.

### How to test

**Stroke**
1. Triage a patient with `vuFocalNeurologicDeficit = true` and chief complaint "sudden facial droop and slurred speech".
2. Call `POST /fasttrack/activate` with `{ visitId, protocol: "STROKE_PROTOCOL" }`.
3. Confirm a HIGH alert fires; the patient appears on the Fast-Track Dashboard with a 4.5-hour clock.

**STEMI**
1. Triage a 55-year-old with `vuChestPain = true`, chief complaint "crushing chest pain radiating to left arm with diaphoresis".
2. Call `POST /fasttrack/activate` with `{ visitId, protocol: "STEMI_PROTOCOL" }`.
3. Confirm the dashboard shows the patient with a 90-min door-to-balloon clock.

---

## 3. Hypoglycemia Enforcement

### What it does

Forces a blood-glucose check on every patient with altered consciousness. Hypoglycemia masquerades as stroke, sepsis, drug overdose, and dementia — every clinician has missed it at least once. The fix is fast, cheap, and lifesaving (IV dextrose). The system makes "check the glucose" a hard step you can't skip.

### How it works

- **Mandatory glucose check** (system raises an alert if not done): `AVPU != ALERT`, OR `hasConvulsions`, OR `hasComa`, OR `vuAlteredMentalStatus`.
- **Recommended check**: known diabetic (chronic conditions text contains "diabetes" / "diabetic" / "DM").

When a glucose value is recorded:
- `< 3.0 mmol/L` → **CRITICAL**, immediate treatment protocol.
- `3.0 – 3.9 mmol/L` → **MILD**, oral carbs.
- `≥ 4.0 mmol/L` → cleared.

Treatment protocol branches on `visit.isPediatric()`:
- **Pediatric**: 5 mL/kg of 10% dextrose IV.
- **Adult**: 50 mL of 50% dextrose IV.

### What triggers it

A triage form filed with the qualifying signs above, or an explicit `POST /hypoglycemia/check/{visitId}` with the glucose value.

### What happens

- `ClinicalAlert` of type `VITAL_SIGN_ABNORMAL`, severity CRITICAL for `< 3.0`.
- A `HypoglycemiaEvent` row is persisted with the protocol recommended.
- Frontend `modules/hypoglycemia/HypoglycemiaView.tsx` shows the alert and treatment instructions.

### How to test

1. Triage a patient with `hasConvulsions = true` (or `AVPU = VOICE`).
2. Call `POST /hypoglycemia/check/{visitId}` with body `{ glucoseValue: 2.5, glucoseUnit: "mmol/L" }`.
3. Confirm a CRITICAL alert fires and the view shows the dextrose protocol with the right adult/peds dose.
4. To exercise the missed-check path: triage with altered consciousness but never call the endpoint — the screen should flag the missing glucose.

---

## 4. Infection Isolation & Public Health

### What it does

Detects infectious-disease presentations and assigns isolation level (STRICT / AIRBORNE / DROPLET / CONTACT). In a Rwandan ED the immediate concern is Ebola (post-2019 outbreak experience), TB (highly endemic), measles (ongoing transmission), meningococcal (sporadic), cholera (rainy-season outbreaks), and COVID. Catching any of these without isolation in the first 5 minutes risks staff and other patients.

### How it works

Priority-ordered rule chain in `InfectionScreeningEngine`:

| Disease | Trigger | Isolation | Risk |
|---|---|---|---|
| **Ebola/Marburg** | `hasFever && hasBleedingSymptoms` + (contact OR recent travel) | STRICT | CONFIRMED |
| **Ebola** (suspected) | `hasFever && hasBleedingSymptoms` only | STRICT | HIGH_RISK |
| **TB** | `hasCough && coughDurationWeeks ≥ 2` + fever + night sweats + weight loss (≥ 3 indicators) | AIRBORNE | HIGH_RISK |
| **Measles** | `hasFever && hasRash` | AIRBORNE | HIGH_RISK |
| **Meningococcal** | `hasFever && hasPurpuricRash` | DROPLET | HIGH_RISK |
| **Cholera** | `hasDiarrhea && hasFever` | CONTACT | MODERATE |
| **COVID** | `hasFever && hasCough` + (recent travel OR contact) | DROPLET | MODERATE |

### What triggers it

`POST /isolation/screen/{visitId}` with the relevant boolean flags. Also auto-evaluates from triage form data.

### What happens

- `ClinicalAlert` of type `VITAL_SIGN_ABNORMAL` (severity matches risk).
- An `InfectionScreening` record is persisted with the suspected disease + isolation level + PPE list.
- Frontend `modules/isolation/IsolationDashboard.tsx` shows every active flagged patient with the PPE checklist (gown, gloves, N95, eye protection).

### How to test

1. Triage a patient.
2. Call `POST /isolation/screen/{visitId}` with body `{ hasFever: true, hasBleedingSymptoms: true, hasContactWithInfectious: true }`.
3. Confirm an Ebola **CONFIRMED** screening with **STRICT** isolation appears on the dashboard.
4. Try other combinations (cough + 3-week duration + night sweats → TB AIRBORNE; fever + rash → Measles AIRBORNE).

---

## 5. Dynamic Re-triage

### What it does

Watches every patient for clinical deterioration after their first triage and **automatically upgrades** their category when new signs appear. ED patients deteriorate silently — a YELLOW patient at registration can be GREEN-skin RED-internally an hour later. The system catches the trajectory and re-triages without waiting for a nurse to walk back to the patient.

### How it works

Three mechanisms working together:

**A. Reassessment scheduler** (`ReassessmentSchedulerService`, 120 s tick)

Each triage category has a max-wait window:
- RED = 0 min, ORANGE = 10 min, YELLOW = 30 min, GREEN = 60 min.

If `(now − lastTriage) > maxWait` and the patient is still in active care, fires a `REASSESSMENT_DUE` alert.
- Severity **CRITICAL** if RED or ≥ 2× overdue, otherwise HIGH.

**B. Waiting-time monitor** (`WaitingTimeMonitorService`, 60 s tick)

Same idea but watches door-to-doctor waits. Fires `WAITING_TIME_EXCEEDED` (CRITICAL or HIGH).

**C. Clinical-sign-event auto-bump** (`RetriageEvaluator`)

When a doctor or nurse logs a new clinical sign on the visit (the `clinical_sign_events` table), the evaluator inspects it:
- **EMERGENCY** sign category, status PRESENT or WORSENING → **AutoBump to RED**, creates a new `TriageRecord` with `isSystemTriggered = true` and `triggering_sign_event_id` linking back to the event.
- **PEDIATRIC_EMERGENCY** sign on a pediatric visit → AutoBump to RED.
- Status downgrade (PRESENT → ABSENT/IMPROVING) → `Suggest(MEDIUM)` for re-evaluation, no auto-bump (down-bumps are manual to avoid premature de-escalation).

### What triggers it

- The schedulers fire on their own tick — no user action required.
- The auto-bump path fires whenever `POST /clinical-signs/event` lands.

### What happens

- `REASSESSMENT_DUE` or `WAITING_TIME_EXCEEDED` alerts on the visit.
- For auto-bumps: a fresh `TriageRecord` is filed (system-triggered), `Visit.currentTriageCategory` flips to RED, the standard zone-routing alert fans out to RESUS topic, and a new entry appears on the doctor's dashboard.
- Frontend `modules/retriage/DynamicRetriage.tsx` shows the watchlist of overdue reassessments + auto-bump audit log.

### How to test

**Reassessment overdue**
1. Triage a patient as YELLOW.
2. Wait > 30 min (don't re-triage). Within ~ 2 min after that the next scheduler tick fires.
3. Confirm `REASSESSMENT_DUE` alert appears on the visit.

**Auto-bump on clinical sign**
1. Triage a patient as ORANGE.
2. As a doctor or nurse, `POST /clinical-signs/event` with `{ visitId, signCode: "<an emergency sign>", status: "PRESENT" }`.
3. Confirm a new RED `TriageRecord` is filed with `isSystemTriggered = true`, the category flips, and the patient now appears on the RESUS zone topic.

---

## 6. ICU Escalation

### What it does

Detects patients on the trajectory toward ICU admission — hemodynamic collapse, respiratory failure, septic shock, post-cardiac-arrest — and notifies the ICU team in real time. ICU beds are scarce in Rwandan EDs and a delayed escalation often means the bed is gone by the time the team arrives.

### How it works

Constants in `IcuEscalationEngine`:
- `MAP_CRITICAL_THRESHOLD = 65.0` mmHg
- `SPO2_CRITICAL_THRESHOLD = 90` %
- `RR_CRITICAL_THRESHOLD = 35` /min
- `GCS_CRITICAL_THRESHOLD = 8`
- `SEPTIC_HR_THRESHOLD = 120`, `SEPTIC_SBP_THRESHOLD = 90`, `SEPTIC_TEMP_THRESHOLD = 38.3`
- `CARDIAC_ARREST_HR_THRESHOLD = 30` (post-cardiac-arrest)

Detection priority order:
1. `HR < 30` → **POST_CARDIAC_ARREST**
2. `GCS ≤ 8` OR `AVPU = UNRESPONSIVE` → **DECREASED_CONSCIOUSNESS**
3. `MAP < 65` → **HEMODYNAMIC_INSTABILITY**
4. `SpO₂ < 90` OR `RR > 35` → **RESPIRATORY_FAILURE**
5. `HR > 120` AND `SBP < 90` AND `Temp > 38.3` → **SEPTIC_SHOCK**

### What triggers it

- `IcuAutoDetectionService` (`@Scheduled fixedRate = 120_000` ms) scans every active visit in RED or ORANGE every 2 minutes.
- Manual: `POST /icu/auto-evaluate/{visitId}` or `POST /icu/request`.

### What happens

- `ClinicalAlert` of type `ICU_ESCALATION_REQUESTED`, severity CRITICAL, `targetZone = RESUS`.
- An `IcuEscalation` record is persisted (lifecycle: REQUESTED → ICU_NOTIFIED → ICU_RESPONDED → BED_ASSIGNED, or DECLINED).
- If the ICU declines because no bed is available: a CRITICAL `ICU_BED_UNAVAILABLE` alert fires with a "consider referral" recommendation.
- Frontend `modules/icu/IcuEscalationView.tsx` shows the queue + acceptance/decline workflow.

### How to test

1. Sign in as a doctor / nurse on a RED or ORANGE patient.
2. Record vitals: `SpO₂ = 88`. (Or any single criterion: SBP 60, GCS 7, etc.)
3. Wait up to 2 min for the auto-detection scheduler, OR call `POST /icu/auto-evaluate/{visitId}` immediately.
4. Confirm a CRITICAL `ICU_ESCALATION_REQUESTED` alert lands on the RESUS topic and the escalation appears on `IcuEscalationView` in REQUESTED state.

---

## 7. Direct Resus Admission (RED bypass)

### What it does

For patients who are "obviously RED on sight" — cardiac arrest rolling in, GSW to chest, full CPR — there is no time for a triage form. The triage nurse declares the patient by clinical eye; the system creates the visit, places them in a RESUS bed, and pages the team. The form gets backfilled later.

### How it works

A single `POST /triage/direct-resus` (also called from `DirectResusModal` in the UI) does, in one transaction:

1. Resolves or creates a Patient (existing patient ID, or a placeholder "Unknown Alpha" / "Unknown Bravo" / etc. for unidentified arrivals).
2. Creates a Visit with `directResus = true`, status REGISTERED.
3. Calls `BedPlacementService` to find a RESUS-zone bed (or AMBULATORY-overflow if all RESUS beds are full — flags `RESUS_OVERFLOW`).
4. Files an immediate auto-RED `TriageRecord` with `decisionPath = "DIRECT_RESUS_ADMISSION: <reason> | declared by <actor>"`.
5. Fires the alert.

If the patient is unidentified, an `IDENTITY_UNRESOLVED` HIGH alert is scheduled to re-fire every 2 hours until identity is resolved (charge-nurse-targeted).

### What triggers it

A clinician explicitly invokes `POST /triage/direct-resus` (or clicks the **Direct Resus** floating button in the UI). The reason is required and must describe the clinical state ("cardiac arrest", "GSW chest", "severe airway compromise").

### What happens

- `ClinicalAlert` of type `DIRECT_RESUS_ADMISSION`, severity CRITICAL, `targetZone = RESUS`, `escalationTier = 1`.
- If no RESUS bed available: extra `RESUS_OVERFLOW` CRITICAL alert with a ranked transfer-candidate list of patients who could be moved out.
- Patient appears on the resus team's dashboard within < 1 second (WebSocket fan-out).
- Frontend: triggered from the `DirectResusFAB` button visible on every authenticated page.

### How to test

1. Sign in as triage nurse / doctor.
2. Click the floating Direct Resus button (red, bottom-right). Or hit the API directly: `POST /triage/direct-resus` with `{ reason: "VF arrest", isPediatric: false, hospitalId: "<your hospital>" }`.
3. Confirm: a new visit appears, marked RED, in a RESUS bed; the resus topic receives a CRITICAL alert; the patient is on every resus-zone clinician's dashboard immediately.
4. To exercise the unidentified path: omit `patientId` → a placeholder "Unknown Alpha" Patient is created. Wait 2+ hours → `IDENTITY_UNRESOLVED` HIGH alert fires for the charge nurse.
5. To exercise overflow: fill every RESUS bed first → next direct resus fires `RESUS_OVERFLOW` with the transfer-candidate ranking.

---

## 8. Lab Critical-Value Engine

### What it does

When the lab files a result that crosses a panic threshold (potassium 6.8, hemoglobin 4.2, lactate 5, etc.), the system flags it as a critical value, alerts the ordering doctor, and tracks acknowledgement. JCI requires panic-value communication within 60 minutes — the engine makes that timeline measurable.

### How it works

`CriticalValueEngine` evaluates every lab result at filing time. Numeric thresholds:

| Test | Critical low | Critical high |
|---|---|---|
| Potassium | < 2.5 mmol/L | > 6.0 mmol/L |
| Sodium | < 120 mmol/L | > 160 mmol/L |
| Glucose | < 2.5 mmol/L | > 25 mmol/L |
| Hemoglobin | < 5 g/dL | — |
| Platelets | < 20,000 | — |
| WBC | < 1,000 (neutropenic) | > 30,000 |
| Creatinine | — | > 10 mg/dL |
| Lactate | — | > 4.0 mmol/L |
| INR | — | > 5.0 |
| pH | < 7.2 | > 7.6 |

Text-based: malaria/RDT/blood-smear/parasit positive → `MALARIA_POSITIVE`; troponin elevated/positive → `TROPONIN_HIGH`.

Then `LabTurnaroundMonitorService` (60 s tick) watches the clock:
- STAT order > 30 min without result → **STAT_LAB_OVERDUE** (HIGH).
- URGENT order > 120 min → **URGENT_LAB_OVERDUE** (HIGH).
- Critical result not acknowledged in 15 min → **CRITICAL_VALUE_UNACKNOWLEDGED** (CRITICAL). Re-broadcasts to the lab topic each cycle so the doctor's banner re-flashes.

### What triggers it

The lab tech files a result through `PUT /lab/{orderId}/result`.

### What happens

- A `ClinicalAlert` of type `CRITICAL_LAB_RESULT` is fired (CRITICAL severity).
- The result lands on the doctor's `CriticalLabBanner` on the dashboard immediately.
- The doctor acknowledges via `AcknowledgeCriticalModal` — JCI-required read-back text + contact method (phone / in-person / in-app).
- If unacked at 15 min, escalation alerts re-fire.

### How to test

1. Create a lab order for a visit (`POST /lab/order` with priority STAT, test name "Potassium").
2. As lab tech, file the result: `PUT /lab/{orderId}/result` with `{ resultNumeric: 6.8, resultUnit: "mmol/L", testName: "Potassium" }`.
3. Confirm: ordering doctor sees the red **Critical Lab Banner** on their dashboard within 1 second; alert recorded.
4. Don't acknowledge. Wait 15 minutes. Confirm: the banner re-flashes, a `CRITICAL_VALUE_UNACKNOWLEDGED` alert is filed, and the alert escalates to charge nurse.
5. Acknowledge via the modal with read-back text. Confirm the banner disappears.

---

## 9. Lab Two-Step Verification

### What it does

Catches typos in critical lab results before they reach the doctor. A junior tech enters the value; a senior tech (HEAD_LAB_TECHNICIAN) sanity-checks it before release. Typos are the #1 lab incident category in audited labs — a senior glance catches them in 20–30 seconds.

### How it works

Activated only when **both** are true:
1. Hospital has `twoStepVerificationEnabled = true`.
2. At least one active `HEAD_LAB_TECHNICIAN` user exists at the hospital.

Gated only on **high-risk** results:
- `order.isCritical` (i.e. the result tripped the critical-value engine), OR
- `request.specimenQualityConcern` (the tech flagged the specimen).

When gated, the result parks in status `AWAITING_VERIFICATION` instead of going to `RESULTED`. The doctor doesn't see it.

Auto-release timeouts to prevent the gate from blocking patient care:
- STAT: 5 min
- URGENT: 15 min
- ROUTINE: 60 min

A scheduler (`autoReleaseTimedOutVerifications`, 60 s tick) flips timed-out orders to RESULTED with `verificationAutoReleased = true`.

Three release paths:
- `POST /lab/{orderId}/verify` — senior signs off.
- `POST /lab/{orderId}/verify-reject` — senior bounces it back to junior with a required reason.
- `POST /lab/{orderId}/release-without-verification` — junior emergency override, required reason logged.

### What triggers it

A high-risk lab result is filed at a hospital with the toggle on AND a senior tech available.

### What happens

- The result enters `AWAITING_VERIFICATION` — does **not** appear on the doctor's view.
- It appears on the senior tech's "Verification" tab in `modules/lab/LabOrdersView.tsx`.
- On verification: status flips to RESULTED, the doctor's banner / view updates, the critical-value alert fires (for the first time — it was held back during gating).
- On rejection: status returns to PROCESSING, junior re-enters.
- On override: same as verification but logged as `verificationOverride = true`.

### How to test

1. Set `hospital.twoStepVerificationEnabled = true`. Ensure ≥ 1 user has designation `HEAD_LAB_TECHNICIAN`.
2. As junior lab tech: file a critical result (`resultNumeric = 6.8, testName = "Potassium"`).
3. Confirm: status is `AWAITING_VERIFICATION`. The ordering doctor sees nothing.
4. As HEAD_LAB_TECHNICIAN: open the **Verification** tab, click **Verify**.
5. Confirm: status is now RESULTED, doctor's critical banner lights up, alert fires.
6. To exercise the timeout: don't verify a STAT order for 5 min → auto-release.
7. To exercise reject: click **Reject (bounce back)** with a reason → junior sees the result back in PROCESSING.

---

## 10. EMS Pre-Arrival & 15-min Re-triage Clock

### What it does

A paramedic in the ambulance pings the receiving ED before the patient arrives. The ED prepares the bay, the patient hits the door, and the receiving nurse acknowledges the handover. Then the system enforces a 15-minute window for the ED to do a formal triage — paramedic field triage is provisional, not final.

### How it works

Paramedic flow:
1. `POST /ems/runs` — paramedic creates a run.
2. `POST /ems/runs/{id}/preregister` — the **pre-arrival ping**. Status flips DISPATCHED → EN_ROUTE. Auto-creates a Visit (with placeholder Patient if unidentified) marked `ambulancePreArrival = true`, with `fieldTriageCategory` set.
3. `POST /ems/runs/{id}/confirm-arrival` — patient is at the door. Status EN_ROUTE → ARRIVED. Sets `Visit.arrivalConfirmedAt = now` and **starts the 15-min re-triage clock**: `Visit.edRetriageDueAt = now + 15 min`.
4. ED nurse opens the inbound run, taps **Acknowledge handover**: status ARRIVED → HANDED_OFF. Records who acknowledged + read-back text.
5. ED nurse files a triage form within 15 min.

`EmsRetriageMonitor` (60 s tick) scans `findRetriageDueBefore(now)`:
- If a TriageRecord exists for the visit → clear `edRetriageDueAt` (no further checks).
- If not → fire **FIELD_TRIAGED_AWAITING_REVIEW** HIGH alert. The charge nurse intervenes.

### What triggers it

Paramedic creates a run and calls `preregister` + `confirm-arrival`. The 15-min monitor runs on its own.

### What happens

- On preregister: `EMS_PRE_ARRIVAL` alert (severity matches field triage — RED → CRITICAL, ORANGE → HIGH, etc.) lands on the receiving ED's dashboard. The `InboundEmsBoard` widget pops the run.
- On confirm-arrival: door clock starts; the inbound card moves from "EN ROUTE" to "AT DOOR".
- On handover ack: run status HANDED_OFF; receiving nurse name + timestamp + read-back recorded.
- If 15 min elapse without ED triage: `FIELD_TRIAGED_AWAITING_REVIEW` HIGH alert.

### How to test

1. Sign in as a paramedic. Create a run; fill in patient context, vitals, field triage YELLOW; tap **Send to ED**.
2. As an ED nurse: confirm the inbound card appears on the dashboard's `InboundEmsBoard` within 1 second.
3. Tap **At ED** to confirm arrival. Door clock starts.
4. Open the run, tap **Acknowledge handover** with a read-back. Confirm status flips to HANDED_OFF.
5. Don't file a triage form for 15 minutes. Confirm a `FIELD_TRIAGED_AWAITING_REVIEW` HIGH alert lands on the charge nurse.

---

## 11. Pediatric Safety Branching

### What it does

Several modules behave differently for children — pediatric weights, dose ranges, triage flowcharts, and emergency signs are not just "smaller adult" rules. A wrong-weight dose can be lethal in pediatrics.

### How it works

Pediatric branching points across the system:

- **Triage form selection** (`TriageService`): `visit.isPediatric()` selects the child triage flowchart (Rwanda MoH child-specific) versus the adult one. The Rwanda triage decision engine itself is the adult flowchart only — pediatric logic is in the form structure.
- **Re-triage evaluator** (`RetriageEvaluator`): a `PEDIATRIC_EMERGENCY` clinical-sign category auto-bumps to RED **only when the visit is pediatric**. On adult visits the same sign returns NO_ACTION.
- **Hypoglycemia treatment** (`HypoglycemiaEnforcementEngine`): branches to 5 mL/kg of 10% dextrose for pediatric, 50 mL of 50% dextrose for adult.
- **Medication dose checks** (`MedicationSafetyEngine.checkDoseRange`): pediatric uses `pediatricMin/MaxDoseMgPerKg × weightKg`; adult uses `adultMin/MaxDoseMg`. **Missing weight on a pediatric patient → warning** (you can't compute a per-kg dose without it).
- **Bed routing** (`BedPlacementService`): pediatric RED falls through to PEDIATRIC zone if a peds-specific bed exists.

### What triggers it

`Patient.isPediatric()` is set at registration based on age (< 13 in current rules). All branching uses this flag.

### What happens

- The right form, the right doses, the right zone — automatically.
- `AlertType.PEDIATRIC_SAFETY` exists in the enum but is not currently raised by any module. If you need a pediatric-specific safety alert, that's a follow-up.

### How to test

1. Register a 5-year-old (`age = 5`). Confirm `isPediatric = true` on the visit.
2. Triage them. Confirm the pediatric triage flowchart loads.
3. Order a hypoglycemia check with `glucose = 2.5`. Confirm the protocol returned is **5 mL/kg of 10% dextrose** (not 50 mL of 50%).
4. Try to prescribe a medication without recording a weight. Confirm the medication-safety engine returns a warning.

---

## 12. Medication Safety Engine

### What it does

Before any medication is administered, the system checks four things in parallel: allergy cross-reactivity, dose range, drug-drug interactions, and duplicate therapy. Catches the prescribing errors that kill or harm — wrong drug in a known-allergic patient, 10× overdose from a decimal-place slip, dangerous combination, second course of the same antibiotic.

### How it works

`MedicationSafetyEngine.validate()` returns a composite result with status (`SAFE` / `WARNING` / `CRITICAL_BLOCK`). The four checks:

**Allergy** (CRITICAL → blocker):
- Direct match: `patient.knownAllergies` contains the drug name.
- Allergen-group match: `formulary.allergenGroups` overlaps the patient's allergens.
- **Cross-reactivity** map: penicillin ↔ beta-lactam / amox / amp / pip / cephalosporin; sulfa ↔ sulfonamide; NSAID ↔ aspirin / ibuprofen / diclofenac / naproxen. A penicillin allergy blocks amoxicillin even if the formulary doesn't list it explicitly.

**Dose range**:
- Pediatric: `pediatricMin/MaxDoseMgPerKg × weightKg`.
- Adult: `adultMin/MaxDoseMg`.
- `< min` → UNDERDOSE warning.
- Within range → NORMAL.
- `> max` and `≤ 2×max` → OVERDOSE warning.
- `> 2×max` → **CRITICAL_OVERDOSE blocker**.

**Drug interactions**: matches `formulary.majorInteractions` against the patient's currently active medications (status PRESCRIBED or ADMINISTERED) → warning per interaction.

**Duplicate therapy**: same `formulary.drugClass` already active on the patient → warning.

### What triggers it

`POST /medsafety/validate` body: `{ patientId, drugName, dose, weightKg }`. Called by the prescribing UI before persisting the order.

### What happens

- If `CRITICAL_BLOCK`: the prescription is blocked. `AlertType.MEDICATION_SAFETY_BLOCK` fires.
- If `WARNING`: the prescription proceeds with a recorded acknowledgement. `AlertType.MEDICATION_SAFETY_WARNING` fires.
- All overrides land in `MedicationSafetyOverridesView` (supervisor / safety-officer audit page).

### How to test

1. Create a patient with `knownAllergies = "penicillin"`.
2. Try to prescribe **Amoxicillin** 500 mg via `POST /medsafety/validate { patientId, drugName: "Amoxicillin", dose: "500mg", weightKg: 70 }`.
3. Confirm: response is **CRITICAL_BLOCK** with reason "cross-reactive with patient's penicillin allergy".
4. Try a pediatric overdose: 5-year-old 18 kg, prescribe Paracetamol 5000 mg. Confirm `CRITICAL_OVERDOSE` block.
5. Try an interaction: patient already on warfarin, prescribe NSAID. Confirm WARNING.

---

## 13. Clinical Pathways

### What it does

Implements step-by-step clinical pathways (sepsis bundle, stroke pathway, STEMI pathway, etc.) so the team has a checklist with timestamps. Each step has a target completion time; overdue steps fire alerts.

### Status

⚠ **Backend logic exists but no HTTP controller is wired up.** The `ClinicalPathwayService`, `PathwayRecommendationEngine`, entities, and seed data are all in place. The frontend (`modules/pathway/ClinicalPathwaysView.tsx`, `api/pathway.ts`) expects an API. It's a **stub at the API boundary** — usable from inside other services, but not from the frontend yet.

### What it would do once wired

- Activate a pathway on a visit (`POST /pathway/activate` — not yet implemented).
- Each pathway step has a target completion time. The service fires `REASSESSMENT_DUE` alerts (severity matches urgency) when steps are overdue.

### How to test (current state)

Not directly testable from the UI today. To exercise the engine, write a small Spring test that calls `ClinicalPathwayService.activateAndRunPathway(visitId, "SEPSIS_BUNDLE")`.

### Recommendation

If clinical pathways are needed soon, the work is small: write a `PathwayController` exposing `POST /pathway/activate`, `POST /pathway/{id}/step/{n}/complete`, `GET /pathway/visit/{visitId}`. ~2 days of backend + FE wiring.

---

## Background schedulers — quick reference

Every clinical-decision module relies on one or more `@Scheduled` jobs to keep watching after the patient stops generating new data. Cheat-sheet:

| Scheduler | File | Tick rate | Fires |
|---|---|---|---|
| Sepsis 1-h bundle monitor | `SepsisBundleMonitorService` | 60 s | Bundle overdue (60 min) / Bundle not started (15 min) |
| Reassessment due | `ReassessmentSchedulerService` | 120 s | `REASSESSMENT_DUE` per category window (RED 0 / ORANGE 10 / YELLOW 30 / GREEN 60 min) |
| Waiting time exceeded | `WaitingTimeMonitorService` | 60 s | `WAITING_TIME_EXCEEDED` |
| ICU auto-detection | `IcuAutoDetectionService` | 120 s | `ICU_ESCALATION_REQUESTED` for unstable RED/ORANGE patients |
| Lab turnaround monitor | `LabTurnaroundMonitorService` | 60 s | `STAT_LAB_OVERDUE` (30 min), `URGENT_LAB_OVERDUE` (120 min), `CRITICAL_VALUE_UNACKNOWLEDGED` (15 min) |
| Lab verification timeout | `LabOrderService.autoReleaseTimedOutVerifications` | 60 s | Auto-release `AWAITING_VERIFICATION` past timeout (STAT 5 / URGENT 15 / ROUTINE 60 min) |
| EMS re-triage clock | `EmsRetriageMonitor` | 60 s | `FIELD_TRIAGED_AWAITING_REVIEW` (15 min after arrival) |
| Identity unresolved | (`DirectResusService` schedule) | hourly | `IDENTITY_UNRESOLVED` HIGH for unidentified patients ≥ 2 h |

If a module ever feels "stuck" (no alert fires when you expected one), the first place to check is the scheduler tick rate — most checks are 60–120 s, so allow up to 2 minutes for the tick to land.

---

## End-to-end smoke test

A 10-minute pass that exercises every module:

1. **Direct Resus** — click the FAB, "VF arrest" → CRITICAL alert, RESUS bed assigned.
2. **EMS pre-arrival** — paramedic creates run, sends pre-arrival → ED inbound board pops.
3. **EMS handover** — ED nurse confirms arrival, acknowledges handover → 15-min retriage clock starts.
4. **Triage form** with `RR=22, SBP=100, AVPU=VOICE` → **Sepsis** screening fires.
5. **Triage form** with `vuChestPain=true`, age 55, "crushing chest pain to left arm" → **Stroke/MI** STEMI fires.
6. **Hypoglycemia** check on a confused patient with glucose 2.5 → CRITICAL.
7. **Isolation**: fever + bleeding + contact → Ebola CONFIRMED, STRICT isolation.
8. **Lab order**, file critical Potassium 6.8 → **Critical-value** alert.
9. **Verification**: with two-step on, the same critical result parks in `AWAITING_VERIFICATION` — senior verifies → released.
10. **ICU**: record `SpO₂ = 88` on a RED patient → **ICU escalation** alert at next 2-min tick.
11. **Med safety**: prescribe amoxicillin to a penicillin-allergic patient → CRITICAL_BLOCK.
12. **Reassessment overdue**: triage YELLOW, wait 32 min → `REASSESSMENT_DUE`.

If all 12 pass, the clinical-decision spine is healthy.
