# SmartTriage — King Faisal Hospital ED Structure Reference & Integration Prompt

> **Purpose:** This document describes the real-world physical layout, staffing model, and patient flow of a tertiary Emergency Department in Rwanda (modeled on King Faisal Hospital Kigali). SmartTriage must be designed to mirror this structure so the software matches how clinicians actually work. Use this as a reference when building UI, designing workflows, and making architectural decisions.

---

## 1. HOSPITAL CONTEXT

**King Faisal Hospital (KFH) Kigali** is Rwanda's premier tertiary referral hospital (~160–200 total beds). Its Emergency Department receives the highest-acuity patients nationally, including referrals from district and provincial hospitals. Insurance systems include Mutuelle de Santé (community-based), RSSB (formal sector), and private insurance.

SmartTriage is designed for this environment — a high-volume, resource-constrained tertiary ED using the **South African Triage Scale (SATS)** with a mix of walk-in, ambulance, and referred patients.

---

## 2. ED PHYSICAL LAYOUT — FUNCTIONAL ZONES

The Emergency Department is organized into distinct functional zones. Patients move through them sequentially based on acuity. SmartTriage must model these zones in its UI and workflow.

### 2.1 Zone Map

| # | Zone | Function | Capacity | Patient Types |
|---|---|---|---|---|
| 1 | **Reception / Registration Desk** | Administrative intake — demographics, insurance verification (Mutuelle/RSSB/private), visit creation | 1–2 desks, waiting area with ~20–30 seats | All arriving patients (walk-in) |
| 2 | **Triage Station** | Clinical assessment by triage nurse — vitals measurement, SATS triage scoring, color category assignment | 1–2 triage bays/rooms with vital signs equipment | All patients after registration |
| 3 | **Resuscitation Room ("Resus")** | Highest acuity — cardiac arrest, major trauma, respiratory failure, shock. Full monitoring, intubation, ventilator, crash cart. | **2–4 bays** (trolley + monitor + crash cart each) | RED category only |
| 4 | **Acute Treatment Area** | Urgent patients needing rapid intervention but not full resuscitation | **4–8 beds/trolley bays** | RED & ORANGE category |
| 5 | **Sub-Acute / General Treatment Area** | Assessment, labs, imaging, treatment for less urgent patients | **6–12 beds/trolley bays** | YELLOW & GREEN category |
| 6 | **Observation Unit** | Short-stay monitoring (6–24 hours) for patients pending discharge or admission decision | **4–8 beds** | Any category, post-treatment |
| 7 | **Isolation Room(s)** | Infectious disease screening and containment (post-Ebola/COVID protocols are standard in Rwandan hospitals). Enclosed with door, ideally negative pressure. | **1–2 enclosed rooms** | Suspected infectious patients |
| 8 | **Pediatric Area** | Separate or semi-separated zone for children (<12 years) with child-sized equipment | **2–4 beds/bays** | Pediatric patients |
| 9 | **Minor Procedures Room** | Suturing, wound care, splinting, abscess drainage, minor surgical procedures | **1–2 enclosed rooms** | Any category, minor procedures |
| 10 | **Central Nursing Station** | Command center — monitors, documentation, communication hub. Line-of-sight to Resus and Acute areas. | Central hub | Staff workspace |

**Total ED capacity: ~20–30 beds/trolley bays** across all zones.

### 2.2 How Patients Are Managed — Bays, Not Rooms

KFH's ED uses a **bay-based open-plan model**:

- **Resuscitation**: Open bays separated by curtains (not enclosed rooms) — allows rapid multi-patient team access and visual oversight
- **Acute & Sub-Acute**: Open-plan areas with trolley bays and curtain dividers. Trolleys (not fixed beds) are standard — allows flexible capacity adjustment
- **Observation**: Semi-private bays or shared rooms
- **Isolation**: Enclosed rooms with doors (infection control requirement — the only fully enclosed patient areas)
- **The ED is NOT a ward.** Patients are on trolleys/stretchers. Once admitted, they transfer to inpatient wards (ICU, Surgical, Medical, Pediatric). The term "bed" in the ED context means "trolley bay."

### 2.3 What This Means for SmartTriage

```
┌─────────────────────────────────────────────────────────────────┐
│  SmartTriage does NOT need to model individual rooms/beds.      │
│  Patients are tracked by VISIT, not by bed/bay number.          │
│  The triage COLOR CATEGORY determines which ZONE they go to.    │
│  The UI should group/filter patients by zone based on triage    │
│  category and visit status.                                     │
└─────────────────────────────────────────────────────────────────┘
```

**UI implications:**
- The **Triage Queue** should visually group patients by color category (RED/ORANGE/YELLOW/GREEN), which maps directly to physical zones
- No need for a "bed management" or "room assignment" module — the triage category IS the zone assignment
- The **Dashboard** should show patient counts per zone/category so charge nurses can see capacity at a glance
- The **Observation Unit** is a special status — patients move there after initial treatment regardless of original triage category

---

## 3. STAFFING STRUCTURE

### 3.1 Shift System

The ED operates **24/7** with a **3-shift model**:

| Shift | Hours | Staffing Level |
|---|---|---|
| **Morning** | 07:00 – 13:00 | Full staffing (peak hours, highest volume) |
| **Afternoon** | 13:00 – 19:00 | Full staffing |
| **Night** | 19:00 – 07:00 | Reduced skeleton crew + on-call backup |

Some departments use 12-hour shifts (07:00–19:00 / 19:00–07:00). As a tertiary facility, KFH has **24/7 in-house physician coverage** in the ED (not just on-call from home).

### 3.2 Doctor Coverage Per Shift

| Role | Count Per Shift | Responsibility |
|---|---|---|
| **Emergency Physician / Medical Officer** | 1–2 | Primary clinical decision-maker in the ED. Assesses patients, orders investigations, prescribes treatment, makes disposition decisions. |
| **Senior Consultant (Specialist)** | 1 on-call (may be present during day) | Oversight, complex cases, teaching. May cover EM, surgery, or internal medicine. |
| **Residents / Registrars** | 2–4 (rotating) | KFH is a teaching hospital (University of Rwanda). Residents rotate through ED under supervision. |
| **On-Call Specialists** | Multiple (not in ED) | Surgery, Internal Medicine, Pediatrics, OB/GYN, Orthopedics, Anesthesia — reachable within minutes for consult/procedure. |

### 3.3 Nursing Coverage Per Shift

| Role | Count Per Shift | Assignment |
|---|---|---|
| **Triage Nurse** | **1** (dedicated) | Stationed at the triage bay. This is the primary SmartTriage user. Takes vitals, performs SATS assessment, assigns triage category. Does NOT leave triage to treat patients. |
| **Resus Nurse(s)** | **1–2** | Dedicated to resuscitation bays. Highest skill level. 1:1 or 1:2 nurse-to-patient ratio. |
| **Acute Area Nurse(s)** | **2–3** | Cover the acute treatment bays. ~1:3 to 1:4 ratio. |
| **General Area Nurse(s)** | **2–3** | Cover sub-acute bays and observation. ~1:4 to 1:6 ratio. |
| **Charge Nurse / Shift Lead** | **1** | Oversees the entire ED nursing team. Manages flow, assignments, escalation. Uses the Dashboard view. |
| **Night Shift (reduced)** | **4–5 total** | 1 triage + 1 resus + 2–3 general. Charge nurse role absorbed by the most senior nurse. |

### 3.4 Other Staff

| Role | Availability | Notes |
|---|---|---|
| **Registrar / Receptionist** | 1 per shift at intake desk | Creates patient records, verifies insurance |
| **Lab Technician** | Lab is separate, runs 24/7 | Specimens sent from ED, results returned electronically or on paper |
| **Radiology Technician** | Available during day, on-call at night | X-ray, ultrasound, CT |
| **Paramedics / EMTs (SAMU)** | Deliver patients, don't staff ED | Rwanda's national ambulance service (SAMU) brings patients, then leaves |
| **Cleaners / Porters** | Per shift | Bay cleaning between patients, patient transport to wards/imaging |
| **Security** | 24/7 | ED entrance control, de-escalation |

### 3.5 What This Means for SmartTriage

```
┌─────────────────────────────────────────────────────────────────┐
│  SmartTriage user roles map directly to hospital roles:         │
│                                                                 │
│  REGISTRAR        → Registration desk staff                     │
│  TRIAGE_NURSE     → The dedicated triage nurse (primary user!)  │
│  NURSE            → Resus, acute, general area nurses           │
│  DOCTOR           → Emergency physicians, residents             │
│  HOSPITAL_ADMIN   → Charge nurse, ED manager                    │
│  LAB_TECHNICIAN   → Lab staff viewing/updating investigations   │
│  SUPER_ADMIN      → Hospital IT/management                      │
└─────────────────────────────────────────────────────────────────┘
```

**UI implications:**
- The **Triage Nurse** sees the Triage Queue + Triage Form primarily. They shouldn't need to navigate deep into clinical documentation.
- **Doctors** need the Visit Detail page with all tabs (vitals, triage history, clinical notes, diagnoses, investigations, medications).
- The **Charge Nurse** needs the Dashboard with hospital-wide overview, alert counts, and capacity indicators.
- **Role-based navigation**: Show/hide menu items and features based on the logged-in user's role. A Registrar doesn't need the Medication Administration Record. A Lab Technician only needs the Investigations view.
- **Shift handoff context**: When nurses/doctors change shift, they need to quickly see the current state of all patients — the Dashboard and Triage Queue serve this purpose.

---

## 4. PATIENT FLOW — ARRIVAL TO DISPOSITION

### 4.1 Complete Flow Diagram

```
                        ┌─────────────┐
                        │   ARRIVAL    │
                        └──────┬──────┘
                               │
                ┌──────────────┼──────────────┐
                ▼              ▼              ▼
          ┌──────────┐  ┌──────────┐  ┌──────────────┐
          │ WALK-IN  │  │AMBULANCE │  │  REFERRAL    │
          │(majority)│  │ (SAMU)   │  │(district hosp│
          └────┬─────┘  └────┬─────┘  └──────┬───────┘
               │              │               │
               ▼              │               ▼
     ┌─────────────────┐     │     ┌─────────────────┐
     │  REGISTRATION   │     │     │  REGISTRATION   │
     │  DESK           │     │     │  DESK           │
     │                 │     │     │  (with referral  │
     │ • Demographics  │     │     │   letter)        │
     │ • Insurance     │     │     └────────┬────────┘
     │ • Visit created │     │              │
     │ Status:         │     │              │
     │ REGISTERED      │     │              │
     └────────┬────────┘     │              │
              │              │              │
              ▼              ▼              ▼
     ┌────────────────────────────────────────────┐
     │            TRIAGE STATION                    │
     │                                              │
     │  Triage Nurse (using SmartTriage):            │
     │  1. Measures vitals (HR, BP, SpO2, Temp, RR) │
     │  2. OR attaches IoT device for auto-vitals    │
     │  3. Assesses emergency signs (ABCD)           │
     │  4. Completes SATS triage form                │
     │  5. System calculates TEWS score              │
     │  6. System assigns triage category            │
     │                                              │
     │  Status: AWAITING_TRIAGE → TRIAGED            │
     │                                              │
     │  ⚠ Ambulance patients may bypass registration │
     │    and come directly here if critical.         │
     │    Registration happens retroactively.         │
     └──────────────────┬─────────────────────────┘
                        │
          ┌─────────────┼─────────────┬──────────────┐
          ▼             ▼             ▼              ▼
     ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐
     │  RED    │  │  ORANGE  │  │  YELLOW  │  │  GREEN  │
     │Immediate│  │ 10 min   │  │ 30 min   │  │ 60 min  │
     │→ RESUS  │  │→ ACUTE   │  │→SUB-ACUTE│  │→GENERAL │
     └────┬────┘  └────┬─────┘  └────┬─────┘  └────┬────┘
          │            │             │              │
          └────────────┼─────────────┴──────────────┘
                       │
                       ▼
     ┌─────────────────────────────────────────────────┐
     │           CLINICAL CARE PHASE                    │
     │                                                  │
     │  Status: IN_TREATMENT                             │
     │                                                  │
     │  Doctor:                                          │
     │  • History & physical examination                 │
     │  • Orders investigations (bloods, imaging, ECG)   │
     │  • Reviews results                                │
     │  • Makes diagnoses                                │
     │  • Prescribes medications                         │
     │  • Writes clinical notes                          │
     │                                                  │
     │  Nurse:                                           │
     │  • Administers medications (MAR workflow)          │
     │  • Monitors vitals (manual or IoT device)         │
     │  • Documents nursing notes                        │
     │  • Countersigns medication administration          │
     │                                                  │
     │  IoT Device (if attached):                        │
     │  • Streams live vitals to SmartTriage              │
     │  • System generates alerts on abnormal values      │
     │  • Deterioration detection (TEWS trending up)      │
     │                                                  │
     │  RE-TRIAGE may occur if patient deteriorates      │
     │  while waiting or during treatment                 │
     └──────────────────┬──────────────────────────────┘
                        │
                        ▼
     ┌─────────────────────────────────────────────────┐
     │         OBSERVATION (if needed)                   │
     │                                                  │
     │  Status: UNDER_OBSERVATION                        │
     │                                                  │
     │  • 6–24 hour monitoring period                    │
     │  • Awaiting lab/imaging results                   │
     │  • Awaiting specialist review                     │
     │  • Monitoring response to treatment               │
     │  • Admission vs. discharge decision pending       │
     └──────────────────┬──────────────────────────────┘
                        │
                        ▼
     ┌─────────────────────────────────────────────────┐
     │         DISPOSITION DECISION                      │
     │                                                  │
     │  Doctor makes final decision:                     │
     └─────┬────────┬────────┬────────┬────────┬───────┘
           ▼        ▼        ▼        ▼        ▼
     ┌─────────┐┌────────┐┌─────────┐┌────────┐┌───────┐
     │DISCHARGE││ADMITTED││TRANSFER ││LEFT    ││DEAD ON│
     │         ││        ││         ││AGAINST ││ARRIVAL│
     │Home with││To ward:││To other ││MEDICAL ││(BLUE) │
     │instruc- ││• ICU   ││hospital ││ADVICE  ││       │
     │tions &  ││• Surg  ││(higher  ││        ││       │
     │follow-up││• Med   ││level or ││        ││       │
     │date     ││• Peds  ││special- ││        ││       │
     │         ││• Maternity│ist)   ││        ││       │
     │COMPLETED││ADMITTED││TRANSFER ││LWBS    ││DOA    │
     └─────────┘└────────┘└─────────┘└────────┘└───────┘
```

### 4.2 Key Flow Details

| Scenario | What Happens | SmartTriage Implication |
|---|---|---|
| **Walk-in (majority ~70–80%)** | Registration → Triage → Treatment → Disposition | Standard flow. Visit status progresses linearly. |
| **Ambulance / Critical** | May bypass registration. Triage + Resus simultaneously. Registered retroactively. | System must allow creating a visit and doing triage before full registration is complete. |
| **Referral from district hospital** | Arrives with a referral letter. Still triaged on arrival (referral status doesn't determine priority). | Triage must be independent of referral — acuity on arrival is what matters. |
| **Pediatric patient (<12 years)** | Directed to pediatric area. Gets child-specific triage form with additional emergency signs. | System auto-detects age <12 and shows pediatric triage fields. |
| **Suspected infectious disease** | Moved to isolation room immediately. Full PPE protocol. | Isolation flag on visit. Consider visual indicator in the queue. |
| **Patient deteriorates while waiting** | Re-triage by the triage nurse. Category may escalate (GREEN→YELLOW→ORANGE→RED). | System supports multiple triage records per visit. Alerts trigger on waiting time exceeded or deterioration. |
| **Boarding** | When inpatient wards are full, admitted patients "board" in the ED on trolleys. Occupies ED capacity. | System should distinguish between ED patients and boarding patients in the dashboard count. |
| **Left without being seen (LWBS)** | Patient leaves before treatment. Visit marked accordingly. | Disposition status option needed. |
| **Average ED stay** | 4–12 hours. Longer if awaiting ward bed (boarding). | Waiting time tracking and alerts are critical. |

### 4.3 SmartTriage Visit Status Mapping

The backend's visit statuses map to the physical flow:

```
REGISTERED            → Patient at Registration Desk
AWAITING_TRIAGE       → Patient in Triage Waiting Area
TRIAGED               → Triage complete, moving to treatment zone
IN_TREATMENT          → Patient in Resus/Acute/Sub-Acute bay
UNDER_OBSERVATION     → Patient in Observation Unit
AWAITING_RESULTS      → Waiting for lab/imaging results
AWAITING_ADMISSION    → Disposition decided (admit), waiting for ward bed
COMPLETED             → Discharged, visit finished
ADMITTED              → Transferred to inpatient ward
TRANSFERRED           → Sent to another hospital
```

---

## 5. PRACTICAL CONSIDERATIONS FOR SmartTriage

### 5.1 The Triage Nurse Is Your #1 User

The triage nurse is the **most important SmartTriage user**. They use the system for every single patient entering the ED. Their workflow must be fast, intuitive, and reliable:

- They see **30–80+ patients per shift** depending on volume
- Average time per triage: **3–5 minutes** (including vitals measurement)
- They work alone at the triage station — no one to ask for tech support
- They switch between adult and pediatric patients constantly
- They need to quickly see "who's next" in the waiting area
- During surges, speed is critical — the UI cannot slow them down

**SmartTriage triage workflow must be completable in under 3 minutes per patient, including vitals entry.**

### 5.2 IoT Device Usage Pattern

The ESP32 IoT monitoring device is NOT attached to every patient. It's used selectively:

- **Resus patients** — always monitored (continuous vitals streaming)
- **Acute patients (ORANGE)** — often monitored, especially if unstable
- **Observation patients** — monitored during observation period
- **Sub-acute/GREEN patients** — rarely monitored (vitals taken manually at triage)
- **Limited devices** — a typical ED may have **4–8 IoT devices** for 20–30 patients

The UI should make it easy to:
1. Start a monitoring session (assign device to patient/visit)
2. See which patients are currently being monitored
3. View live vitals for monitored patients
4. Receive alerts when monitored vitals go abnormal
5. Stop monitoring when no longer needed (frees the device)

### 5.3 Alerts That Actually Matter

In a busy ED, alert fatigue is a real danger. SmartTriage alerts must be prioritized:

| Alert Type | Urgency | Who Sees It | Example |
|---|---|---|---|
| **Critical vital sign** | IMMEDIATE | Assigned nurse + doctor + charge nurse | SpO2 < 90%, HR > 150 |
| **TEWS score critical** | IMMEDIATE | Triage nurse + charge nurse | TEWS ≥ 7, category should be RED |
| **Deterioration detected** | HIGH | Assigned nurse + doctor | TEWS increasing over time |
| **Sepsis screening positive** | HIGH | Doctor | Meets SIRS/qSOFA criteria |
| **Waiting time exceeded** | MEDIUM | Triage nurse + charge nurse | ORANGE patient waiting >10 min |
| **IoT device issue** | LOW | Charge nurse | Device disconnected, low battery |

### 5.4 Night Shift Considerations

Night shift has reduced staffing. SmartTriage is even MORE valuable at night:
- Fewer eyes on patients → system alerts become the safety net
- Triage nurse may also cover some general nursing duties → needs quick context switching
- Doctor may be alone → needs comprehensive patient view quickly
- Lab/imaging delays → longer observation times → more important to track waiting times

### 5.5 Handoff Between Shifts

Shift change (07:00, 13:00, 19:00) is a critical safety moment. SmartTriage supports this by:
- **Dashboard** gives incoming team an instant overview of department state
- **Triage Queue** shows all active patients sorted by acuity and waiting time
- **Visit Detail** preserves all clinical documentation from the previous shift
- **Alert history** shows what happened during the previous shift
- **No information is lost in verbal handoff** — everything is in the system

### 5.6 Infrastructure Realities

Rwandan hospitals face practical constraints:
- **Internet**: Generally reliable in Kigali (fiber/4G) but can have outages
- **Power**: KFH has generator backup, but brief outages occur during switchover
- **Devices**: Staff may use shared desktop PCs at nursing stations, or tablets on the floor
- **Training**: Not all staff are tech-savvy — UI must be extremely intuitive
- **Language**: Clinical staff are fluent in English (Rwanda's education system uses English). Some may prefer Kinyarwanda for non-clinical terms. UI should be in English (clinical standard).

---

## 6. SUMMARY — KEY DESIGN PRINCIPLES FROM HOSPITAL STRUCTURE

1. **Bay-based, not room-based** — Track patients by visit and triage category, not by bed number.
2. **Color is king** — RED/ORANGE/YELLOW/GREEN is the universal language. It determines where the patient goes, who sees them, and how fast.
3. **Triage nurse is the primary user** — Optimize every pixel of the triage workflow for speed and clarity.
4. **Role-based views** — Different staff need different views. Don't show a registrar the medication chart.
5. **Alerts must cut through noise** — In a 30-patient ED with 4 nurses, only actionable alerts should interrupt.
6. **Shift-resilient** — All data persists. The system IS the handoff tool.
7. **IoT is selective** — Not every patient gets a device. Make it easy to assign/unassign.
8. **Speed matters** — Triage in <3 min. Dashboard loads instantly. No multi-step workflows for urgent actions.
9. **Expect the worst** — Patients deteriorate, devices disconnect, shifts change mid-crisis. The system must handle all of it gracefully.
10. **This is Kigali, not Boston** — Design for shared PCs, occasional connectivity issues, and variable tech literacy. Premium UI does not mean complex UI.

---

**Use this document alongside the FRONTEND_INTEGRATION_PROMPT.md and SMARTTRIAGE_TECHNICAL_DOCUMENTATION.md to ensure the system mirrors the actual clinical environment it will be deployed in.**
