# SMARTTRIAGE — Full-Stack Integration Prompt

You are being given a **production-grade Spring Boot backend** (SmartTriage-server) and a **React/Next.js frontend** (SmartTriage_Frontend_V6). Your mission is to **connect them into a fully functional, end-to-end healthcare triage application**. This is NOT a toy project — it's a real medical system being deployed in Rwanda. Every decision must be clinically sound, secure, and production-ready.

---

## ⚠️ CRITICAL — FRONTEND CURRENT STATUS (READ FIRST)

**The frontend (SmartTriage_Frontend_V6) already has COMPLETE, PRODUCTION-QUALITY implementations of the following features:**

| Feature | Status | Directive |
|---|---|---|
| **Dashboards** | ✅ COMPLETE & PERFECT | DO NOT REDESIGN — only wire up real API data if using mock/static data |
| **Patient Registration** | ✅ COMPLETE & PERFECT | DO NOT REDESIGN — only connect to backend endpoints |
| **Patient Registry (Patient List)** | ✅ COMPLETE & PERFECT | DO NOT REDESIGN — only connect to backend endpoints |
| **Triage Queue** | ✅ COMPLETE & PERFECT | DO NOT REDESIGN — only connect to backend endpoints |
| **Constant Monitoring (Real-Time Vitals)** | ✅ COMPLETE & PERFECT | DO NOT REDESIGN — only connect to WebSocket + backend endpoints |

### What this means for you:

1. **DO NOT** touch the layout, styling, component structure, animations, or visual design of these existing features. They are premium and polished — leave them exactly as they are.
2. **DO** connect them to the real backend API endpoints (replace any mock/static/hardcoded data with live API calls).
3. **DO** add any MISSING features that the backend supports but the frontend doesn't have yet (see "NEEDS IMPLEMENTATION" list below).
4. **ALL new pages/components you create MUST match the existing premium UI aesthetics** — same design system, same color palette, same typography, same spacing, same component patterns, same animations. Study the existing code and replicate its quality exactly.
5. **When in doubt, PRESERVE the existing implementation.** If something looks intentional, it probably is.

### NEEDS IMPLEMENTATION (features the backend supports but the frontend may be missing):
- **Complete Triage Form (SATS)** — The full ~70-field adult + pediatric triage form with TEWS scoring
- **Clinical Notes** — Create/view clinical notes by type (16 types)
- **Diagnoses Management** — Add/manage diagnoses (provisional, confirmed, differential, working)
- **Investigations** — Order, track status, record results (full workflow)
- **Medication Administration Record (MAR)** — Prescribe, administer, countersign medications
- **Alert Dashboard** — Hospital-wide clinical alerts with severity + acknowledgment
- **IoT Device Management** — Register devices, view status, start/stop monitoring sessions
- **Admin: Hospital Management** — CRUD hospitals (SUPER_ADMIN only)
- **Admin: User Management** — CRUD staff users (SUPER_ADMIN, HOSPITAL_ADMIN)
- **Visit Detail Page** — Full clinical workspace with tabs for vitals, triage, notes, diagnoses, investigations, medications, real-time monitor, alerts

**PRIORITY ORDER:** Auth connection → API service layer → Wire existing pages to real data → Build missing features (visit detail first, then clinical docs, then admin pages)

---

## 1. PROJECT CONTEXT — What Is SmartTriage?

SmartTriage is a **hospital emergency department triage and patient monitoring system** built for resource-constrained healthcare settings in Sub-Saharan Africa (initially Rwanda). It implements the **South African Triage Scale (SATS)** — a validated 5-color triage system used across Africa.

### What it does:
1. **Patient Registration** — Register patients with demographics, medical history, emergency contacts
2. **Visit Management** — Track patient visits through a complete ED workflow: Registration → Triage → Assessment → Treatment → Disposition
3. **Triage (SATS)** — A structured clinical decision engine that:
   - Evaluates emergency signs (airway, breathing, circulation, consciousness)
   - Calculates TEWS (Triage Early Warning Score) from vitals + mobility + AVPU + trauma
   - Runs a decision tree through Very Urgent → Urgent → Routine categories
   - Supports BOTH adult and pediatric (child-specific) triage forms
   - Assigns color category: RED (immediate), ORANGE (10 min), YELLOW (30 min), GREEN (60 min), BLUE (dead on arrival)
4. **Vital Signs** — Manual entry + real-time IoT device monitoring (ESP32 with SpO2, HR, temperature, respiratory rate, ECG)
5. **Clinical Documentation** — Clinical notes (16 types), diagnoses (provisional/confirmed/differential/working), investigations (lab, radiology, ECG, etc.), medications (full MAR workflow: prescribe → administer → countersign)
6. **IoT Real-Time Monitoring** — ESP32 medical devices stream vitals via REST, persisted as VitalStream records, pushed to frontend via WebSocket (STOMP)
7. **Alert System** — Automatic clinical alerts (TEWS critical, vital sign abnormal, deterioration detected, sepsis screening, waiting time exceeded, IoT device issues)
8. **Multi-Hospital** — Supports multiple hospitals, each with their own staff, patients, devices
9. **Role-Based Access** — 9 roles with granular permissions: SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE, REGISTRAR, PARAMEDIC, LAB_TECHNICIAN, READ_ONLY

### Clinical Workflow (the happy path):
```
Registrar registers patient → Creates visit (REGISTERED)
→ Visit moves to AWAITING_TRIAGE
→ Triage Nurse performs triage (SATS form with emergency signs, TEWS, decision tree)
→ Visit becomes TRIAGED with color category (RED/ORANGE/YELLOW/GREEN)
→ Patient enters queue sorted by triage severity
→ Doctor begins assessment (UNDER_ASSESSMENT)
→ Doctor orders investigations (labs, imaging), records diagnoses, prescribes medications
→ Treatment proceeds (UNDER_TREATMENT / UNDER_OBSERVATION)
→ Disposition: DISCHARGED / ADMITTED / TRANSFERRED / ICU_ADMITTED
```

Throughout this workflow, IoT devices can be attached to patients, streaming vitals in real-time, triggering automatic alerts and re-triages when deterioration is detected.

---

## 2. BACKEND ARCHITECTURE (SmartTriage-server)

**Stack:** Spring Boot 4.0.3, Java 21, PostgreSQL 14, Flyway migrations, JWT auth, WebSocket (STOMP), Lombok

**Base URL:** `http://localhost:8080`
**API prefix:** `/api/v1/`

### 2.1 Module Structure

The backend has **11 modules**, each with Controller → Service → Repository → Entity → DTO layers:

| Module | Purpose | Base Path |
|--------|---------|-----------|
| **auth** | Login, JWT tokens, refresh | `/api/v1/auth` |
| **hospital** | Hospital CRUD | `/api/v1/hospitals` |
| **user** | Staff user management | `/api/v1/users` |
| **patient** | Patient registration & search | `/api/v1/patients` |
| **visit** | Visit lifecycle management | `/api/v1/visits` |
| **vital** | Manual vital signs recording | `/api/v1/vitals` |
| **triage** | SATS triage engine | `/api/v1/triage` |
| **clinical** | Notes, diagnoses, investigations | `/api/v1/clinical-notes`, `/api/v1/diagnoses`, `/api/v1/investigations` |
| **medication** | Medication administration record | `/api/v1/medications` |
| **iot** | Device mgmt, monitoring sessions, vital stream | `/api/v1/iot` |
| **alert** | Clinical alerts | `/api/v1/alerts` |

### 2.2 API Response Wrapper

**EVERY** backend response is wrapped in this structure:
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },           // The actual payload (or Page object for paginated)
  "timestamp": "2026-03-05T10:30:00Z"
}
```

For paginated endpoints, `data` contains a Spring `Page` object:
```json
{
  "success": true,
  "data": {
    "content": [ ... ],       // Array of items
    "totalElements": 150,
    "totalPages": 8,
    "number": 0,              // Current page (0-indexed)
    "size": 20,               // Page size
    "first": true,
    "last": false
  }
}
```

Error responses:
```json
{
  "success": false,
  "message": "Patient not found with ID: ...",
  "data": null,
  "timestamp": "2026-03-05T10:30:00Z"
}
```

Validation errors:
```json
{
  "success": false,
  "message": "Validation failed",
  "data": { "email": "Invalid email format", "firstName": "First name is required" }
}
```

---

## 3. COMPLETE API ENDPOINT REFERENCE

### 3.1 AUTH — `/api/v1/auth`

| Method | Path | Body | Response | Auth |
|--------|------|------|----------|------|
| POST | `/login` | `LoginRequest` | `ApiResponse<AuthResponse>` | Public |
| POST | `/refresh` | `RefreshTokenRequest` | `ApiResponse<AuthResponse>` | Public |

**LoginRequest:**
```json
{ "email": "admin@smarttriage.com", "password": "SmartTriage@2026" }
```

**AuthResponse:**
```json
{
  "accessToken": "eyJhbG...",
  "refreshToken": "eyJhbG...",
  "tokenType": "Bearer",
  "userId": "uuid",
  "email": "admin@smarttriage.com",
  "firstName": "System",
  "lastName": "Administrator",
  "role": "SUPER_ADMIN",
  "hospitalId": "uuid",
  "hospitalName": "SmartTriage Central"
}
```

**JWT Details:**
- Access token: 15 minutes expiry, contains claims: `sub` (email), `hospitalId`, `role`
- Refresh token: 24 hours expiry
- Header: `Authorization: Bearer <accessToken>`
- Account locks after 5 failed login attempts

**RefreshTokenRequest:**
```json
{ "refreshToken": "eyJhbG..." }
```

### 3.2 HOSPITAL — `/api/v1/hospitals`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `CreateHospitalRequest` | `ApiResponse<HospitalResponse>` (201) | SUPER_ADMIN |
| GET | `/{id}` | — | `ApiResponse<HospitalResponse>` | Authenticated |
| GET | `/code/{code}` | — | `ApiResponse<HospitalResponse>` | Authenticated |
| GET | `/` | `?page=0&size=20` | `ApiResponse<Page<HospitalResponse>>` | SUPER_ADMIN |
| DELETE | `/{id}` | — | `ApiResponse<Void>` | SUPER_ADMIN |

**CreateHospitalRequest:**
```json
{
  "name": "Kigali Central Hospital",              // @NotBlank @Size(max=255)
  "hospitalCode": "KCH-001",                       // @NotBlank @Size(max=20)
  "address": "KN 43 Street, Nyarugenge",
  "city": "Kigali",
  "province": "Kigali City",
  "country": "RWA",                                 // @Size(max=3) ISO 3166-1 alpha-3
  "phoneNumber": "+250788000000",                   // @Size(max=20)
  "email": "info@kch.rw",                           // @Email
  "tier": "Tertiary",
  "bedCapacity": 500,
  "edCapacity": 50,
  "icuCapacity": 20
}
```

**HospitalResponse:** Same fields + `id` (UUID), `createdAt`, `updatedAt`

### 3.3 USER — `/api/v1/users`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `CreateUserRequest` | `ApiResponse<UserResponse>` (201) | SUPER_ADMIN, HOSPITAL_ADMIN |
| GET | `/{id}` | — | `ApiResponse<UserResponse>` | Authenticated |
| GET | `/hospital/{hospitalId}` | `?page=0&size=20` | `ApiResponse<Page<UserResponse>>` | Authenticated |
| DELETE | `/{id}` | — | `ApiResponse<Void>` | SUPER_ADMIN, HOSPITAL_ADMIN |

**CreateUserRequest:**
```json
{
  "firstName": "John",                              // @NotBlank @Size(max=100)
  "lastName": "Doe",                                // @NotBlank @Size(max=100)
  "email": "john.doe@hospital.rw",                  // @NotBlank @Email
  "password": "SecurePass123!",                     // @NotBlank @Size(min=8, max=128)
  "phoneNumber": "+250788111111",                   // @Size(max=20)
  "role": "DOCTOR",                                 // @NotNull — see Role enum below
  "employeeNumber": "EMP-001",
  "professionalLicense": "MD-RW-12345",
  "department": "Emergency",
  "hospitalId": "uuid"                              // @NotNull
}
```

**Role enum values:** `SUPER_ADMIN`, `HOSPITAL_ADMIN`, `DOCTOR`, `TRIAGE_NURSE`, `NURSE`, `REGISTRAR`, `PARAMEDIC`, `LAB_TECHNICIAN`, `READ_ONLY`

**UserResponse:** Same fields (minus password) + `id`, `hospitalName`, `createdAt`, `updatedAt`

### 3.4 PATIENT — `/api/v1/patients`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `CreatePatientRequest` | `ApiResponse<PatientResponse>` (201) | SUPER_ADMIN, HOSPITAL_ADMIN, REGISTRAR, NURSE, TRIAGE_NURSE, DOCTOR |
| GET | `/{id}` | — | `ApiResponse<PatientResponse>` | Authenticated |
| GET | `/hospital/{hospitalId}` | `?page=0&size=20` | `ApiResponse<Page<PatientResponse>>` | Authenticated |
| GET | `/hospital/{hospitalId}/search` | `?query=John&page=0&size=20` | `ApiResponse<Page<PatientResponse>>` | Authenticated |

**CreatePatientRequest:**
```json
{
  "firstName": "Jean",                              // @NotBlank @Size(max=100)
  "lastName": "Mutesi",                             // @NotBlank @Size(max=100)
  "dateOfBirth": "1990-05-15",                      // LocalDate (YYYY-MM-DD)
  "gender": "FEMALE",                               // Gender enum: MALE, FEMALE, OTHER, UNKNOWN
  "nationalId": "1199080012345678",                 // @Size(max=30) — Rwanda national ID
  "phoneNumber": "+250788222222",                   // @Size(max=20)
  "address": "Kigali, Gasabo District",
  "emergencyContactName": "Marie Uwimana",
  "emergencyContactPhone": "+250788333333",
  "bloodType": "O+",
  "knownAllergies": "Penicillin",
  "chronicConditions": "Asthma",
  "hospitalId": "uuid"                              // @NotNull
}
```

**PatientResponse:** Same fields + `id`, `medicalRecordNumber` (auto-generated: MRN-YYYYMMDD-XXXX), `ageInYears` (computed), `isPediatric` (computed: age < 12), `createdAt`, `updatedAt`

### 3.5 VISIT — `/api/v1/visits`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `CreateVisitRequest` | `ApiResponse<VisitResponse>` (201) | SUPER_ADMIN, HOSPITAL_ADMIN, REGISTRAR, NURSE, TRIAGE_NURSE, DOCTOR |
| GET | `/{id}` | — | `ApiResponse<VisitResponse>` | Authenticated |
| GET | `/hospital/{hospitalId}/active` | `?page=0&size=50` | `ApiResponse<Page<VisitResponse>>` | Authenticated |
| GET | `/patient/{patientId}` | `?page=0&size=20` | `ApiResponse<Page<VisitResponse>>` | Authenticated |
| GET | `/hospital/{hospitalId}/status/{status}` | `?page=0&size=50` | `ApiResponse<Page<VisitResponse>>` | Authenticated |
| PATCH | `/{id}/status` | `?status=TRIAGED` | `ApiResponse<VisitResponse>` | SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |

**CreateVisitRequest:**
```json
{
  "patientId": "uuid",                              // @NotNull
  "hospitalId": "uuid",                             // @NotNull
  "arrivalMode": "WALK_IN",                         // ArrivalMode enum (optional)
  "chiefComplaint": "Fever and headache for 3 days",
  "referringFacility": "Kibagabaga District Hospital"
}
```

**ArrivalMode enum:** `WALK_IN`, `AMBULANCE`, `REFERRAL`, `POLICE`, `HELICOPTER`, `OTHER`

**VisitStatus enum (the full lifecycle):**
`REGISTERED` → `AWAITING_TRIAGE` → `TRIAGED` → `AWAITING_ASSESSMENT` → `UNDER_ASSESSMENT` → `UNDER_TREATMENT` → `UNDER_OBSERVATION` → `PENDING_DISPOSITION` → `DISCHARGED` / `ADMITTED` / `TRANSFERRED` / `ICU_ADMITTED` / `LEFT_WITHOUT_BEING_SEEN` / `DECEASED`

**VisitResponse:**
```json
{
  "id": "uuid",
  "visitNumber": "VIS-20260305-0001",               // Auto-generated
  "patientId": "uuid",
  "patientName": "Jean Mutesi",
  "hospitalId": "uuid",
  "arrivalMode": "WALK_IN",
  "arrivalTime": "2026-03-05T10:30:00Z",            // Auto-set at creation
  "chiefComplaint": "Fever and headache for 3 days",
  "status": "AWAITING_TRIAGE",
  "currentTriageCategory": "YELLOW",                 // Set after triage
  "currentTewsScore": 4,                             // Set after triage
  "triageTime": "2026-03-05T10:35:00Z",
  "assessmentStartTime": null,
  "dispositionType": null,
  "dispositionTime": null,
  "dispositionNotes": null,
  "referringFacility": null,
  "isPediatric": false,
  "retriageCount": 0,
  "createdAt": "2026-03-05T10:30:00Z",
  "updatedAt": "2026-03-05T10:35:00Z"
}
```

### 3.6 VITAL SIGNS — `/api/v1/vitals`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `RecordVitalsRequest` | `ApiResponse<VitalSignsResponse>` (201) | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE, PARAMEDIC |
| GET | `/visit/{visitId}` | `?page=0&size=50` | `ApiResponse<Page<VitalSignsResponse>>` | Authenticated |
| GET | `/visit/{visitId}/latest` | — | `ApiResponse<VitalSignsResponse>` | Authenticated |

**RecordVitalsRequest:**
```json
{
  "visitId": "uuid",                                // @NotNull
  "respiratoryRate": 18,                            // @Min(0) @Max(80) breaths/min
  "heartRate": 78,                                  // @Min(0) @Max(300) bpm
  "systolicBp": 120,                                // @Min(0) @Max(300) mmHg
  "diastolicBp": 80,                                // @Min(0) @Max(200) mmHg
  "temperature": 36.8,                              // @Min(25) @Max(45) °C
  "spo2": 98,                                       // @Min(0) @Max(100) %
  "avpu": "ALERT",                                  // AvpuScore enum
  "bloodGlucose": 5.5,                              // mmol/L
  "painScore": 3,                                   // @Min(0) @Max(10)
  "gcsScore": 15,                                   // @Min(3) @Max(15)
  "source": "MANUAL_ENTRY",                         // VitalSource enum (default)
  "deviceId": null,                                 // Set if from IoT device
  "notes": "Patient appears comfortable"
}
```

**AvpuScore enum:** `ALERT` (0 TEWS pts), `CONFUSED` (1), `VERBAL` (1), `PAIN` (2), `UNRESPONSIVE` (3)
**VitalSource enum:** `MANUAL_ENTRY`, `IOT_DEVICE`, `AMBULANCE_MONITOR`, `IMPORTED`

### 3.7 TRIAGE — `/api/v1/triage`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `PerformTriageRequest` | `ApiResponse<TriageRecordResponse>` (201) | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE |
| GET | `/visit/{visitId}/history` | `?page=0&size=20` | `ApiResponse<Page<TriageRecordResponse>>` | Authenticated |
| GET | `/visit/{visitId}/latest` | — | `ApiResponse<TriageRecordResponse>` | Authenticated |

**PerformTriageRequest** (this is the LARGEST DTO — ~70 fields, mirrors the SATS paper form):
```json
{
  "visitId": "uuid",                                // @NotNull

  // ─── SECTION 1: Emergency Signs (any TRUE → RED category) ───
  "hasAirwayCompromise": false,
  "hasBreathingDistress": false,
  "hasSevereRespiratoryDistress": false,
  "hasCardiacArrest": false,
  "hasUncontrolledHaemorrhage": false,
  "hasStabGunWoundNeckChest": false,
  "hasConvulsions": false,
  "convulsionGlucose": null,                        // Required if hasConvulsions=true
  "hasComa": false,
  "comaGlucose": null,                              // Required if hasComa=true
  "hasHypoglycaemia": false,
  "hasPurpuricRash": false,
  "hasBurnFaceInhalation": false,

  // ─── SECTION 1b: Child-Specific Emergency Signs ───
  "childCentralCyanosis": false,
  "childPulseLowOrAbsent": false,
  "childColdHandsComposite": false,
  "childColdHandsLethargic": false,
  "childColdHandsPulseWeakFast": false,
  "childColdHandsCapRefill": false,
  "childSevereDehydration": false,
  "childDehydrationSkinPinch": false,
  "childDehydrationLethargy": false,
  "childDehydrationSunkenEyes": false,
  "childWeightKg": null,
  "childHeightCm": null,

  // ─── SECTION 2: TEWS Components ───
  "mobility": "WALKING",                            // @NotNull — MobilityStatus enum
  "avpu": "ALERT",                                  // @NotNull — AvpuScore enum
  "traumaStatus": "NO_TRAUMA",                      // @NotNull — TraumaStatus enum
  "vitalSignsId": "uuid",                           // Links to a VitalSigns record (optional)

  // ─── SECTION 3: Very Urgent — Medical (any TRUE → ORANGE) ───
  "vuFocalNeurologicDeficit": false,
  "vuAlteredMentalStatus": false,
  "vuNeurologicalGlucose": null,
  "vuChestPain": false,
  "vuPoisoningOverdose": false,
  "vuPregnantAbdominalPain": false,
  "vuCoughingVomitingBlood": false,
  "vuDiabeticHighGlucose": false,
  "vuDiabeticGlucose": null,
  "vuAggression": false,
  "vuShortnessOfBreath": false,

  // ─── SECTION 3: Very Urgent — Trauma (any TRUE → ORANGE) ───
  "vuBurnOver20Percent": false,
  "vuOpenFracture": false,
  "vuThreatenedLimb": false,
  "vuEyeInjury": false,
  "vuLargeJointDislocation": false,
  "vuSevereMechanismOfInjury": false,
  "vuVerySeverePain": false,
  "vuPregnantAbdominalTrauma": false,

  // ─── SECTION 4: Urgent Signs (any TRUE → YELLOW) ───
  "urgUnableToDrinkVomits": false,
  "urgAbdominalPain": false,
  "urgVeryPale": false,
  "urgPregnantVaginalBleeding": false,
  "urgDiabeticVeryHighGlucose": false,
  "urgDiabeticGlucose": null,
  "urgFingerToeDislocation": false,
  "urgClosedFracture": false,
  "urgBurnWithoutUrgentSigns": false,
  "urgPregnantTraumaNonAbdominal": false,
  "urgModeratePain": false,
  "urgLacerationAbscess": false,
  "urgForeignBodyAspiration": false,

  // ─── SECTION 5: Clinical Metadata ───
  "presentingComplaints": "High fever, headache, body aches",
  "clinicalNotes": "Patient alert, oriented, no signs of distress",

  // ─── Special Considerations ───
  "specialAcuteTrauma": false,
  "specialSeizureHistory": false,
  "specialAssaultAbuse": false,
  "specialSuicideAttempt": false
}
```

**MobilityStatus enum:** `WALKING` (0 TEWS pts), `WITH_HELP` (1), `STRETCHER` (2)
**TraumaStatus enum:** `NO_TRAUMA` (0 TEWS pts), `TRAUMA` (1)

**TriageRecordResponse:** All input fields + computed results:
```json
{
  "id": "uuid",
  "visitId": "uuid",
  "triagedById": "uuid",
  "triagedByName": "Nurse Uwimana",
  "vitalSignsId": "uuid",
  "triageTime": "2026-03-05T10:35:00Z",
  // ... all input fields echoed back ...
  "tewsScore": 4,                                   // Computed TEWS (0-17)
  "triageCategory": "YELLOW",                       // Computed category
  "decisionPath": "TEWS≥3 + Urgent Signs → YELLOW", // Human-readable decision trace
  "isChildForm": false,
  "isRetriage": false,
  "isSystemTriggered": false,
  "previousCategory": null,
  "triageNurseName": "Nurse Uwimana",
  "notifiedDoctorName": null,
  "doctorNotifiedAt": null,
  "attendingDoctorName": null,
  "doctorAttendedAt": null,
  "createdAt": "2026-03-05T10:35:00Z"
}
```

**TriageCategory enum:**
| Value | Description | Max Wait | Severity |
|-------|-------------|----------|----------|
| `RED` | Immediate | 0 min | 4 |
| `ORANGE` | Very Urgent | 10 min | 3 |
| `YELLOW` | Urgent | 30 min | 2 |
| `GREEN` | Routine | 60 min | 1 |
| `BLUE` | Dead on Arrival | N/A | 0 |

### 3.8 CLINICAL NOTES — `/api/v1/clinical-notes`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `CreateClinicalNoteRequest` | `ApiResponse<ClinicalNoteResponse>` (201) | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| PUT | `/{id}` | `CreateClinicalNoteRequest` | `ApiResponse<ClinicalNoteResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| DELETE | `/{id}` | — | `ApiResponse<Void>` | SUPER_ADMIN, DOCTOR |
| GET | `/{id}` | — | `ApiResponse<ClinicalNoteResponse>` | Authenticated |
| GET | `/visit/{visitId}` | `?page=0&size=50` | `ApiResponse<Page<ClinicalNoteResponse>>` | Authenticated |
| GET | `/visit/{visitId}/all` | — | `ApiResponse<List<ClinicalNoteResponse>>` | Authenticated |
| GET | `/visit/{visitId}/type/{type}` | — | `ApiResponse<List<ClinicalNoteResponse>>` | Authenticated |
| GET | `/visit/{visitId}/type/{type}/latest` | — | `ApiResponse<ClinicalNoteResponse>` | Authenticated |

**CreateClinicalNoteRequest:**
```json
{
  "visitId": "uuid",                                // @NotNull
  "noteType": "PROGRESS_NOTE",                      // @NotNull — NoteType enum
  "content": "Patient improving, fever subsiding",  // @NotBlank
  "recordedByName": "Dr. Gasana",
  "section": "Assessment"
}
```

**NoteType enum (16 types):** `PHYSICAL_FINDINGS`, `PROGRESS_NOTE`, `NURSING_NOTE`, `DOCTOR_NOTE`, `TRIAGE_NOTE`, `HISTORY_OF_PRESENTING_COMPLAINT`, `PAST_MEDICAL_HISTORY`, `SOCIAL_HISTORY`, `FAMILY_HISTORY`, `REVIEW_OF_SYSTEMS`, `ALLERGIES`, `CURRENT_MEDICATIONS`, `TREATMENT_PLAN`, `DISCHARGE_SUMMARY`, `HANDOVER`, `OTHER`

### 3.9 DIAGNOSES — `/api/v1/diagnoses`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `CreateDiagnosisRequest` | `ApiResponse<DiagnosisResponse>` (201) | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE |
| PUT | `/{id}` | `CreateDiagnosisRequest` | `ApiResponse<DiagnosisResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE |
| DELETE | `/{id}` | — | `ApiResponse<Void>` | SUPER_ADMIN, DOCTOR |
| GET | `/{id}` | — | `ApiResponse<DiagnosisResponse>` | Authenticated |
| GET | `/visit/{visitId}` | `?page=0&size=20` | `ApiResponse<Page<DiagnosisResponse>>` | Authenticated |
| GET | `/visit/{visitId}/all` | — | `ApiResponse<List<DiagnosisResponse>>` | Authenticated |
| GET | `/visit/{visitId}/type/{type}` | — | `ApiResponse<List<DiagnosisResponse>>` | Authenticated |

**CreateDiagnosisRequest:**
```json
{
  "visitId": "uuid",                                // @NotNull
  "diagnosisType": "PROVISIONAL",                   // @NotNull — DiagnosisType enum
  "icdCode": "A09",                                 // ICD-10 code (optional)
  "description": "Acute gastroenteritis",           // @NotBlank
  "diagnosedByName": "Dr. Gasana",
  "isPrimary": true,                                // Only one primary per visit
  "notes": "Based on clinical presentation"
}
```

**DiagnosisType enum:** `PROVISIONAL`, `CONFIRMED`, `DIFFERENTIAL`, `WORKING`

### 3.10 INVESTIGATIONS — `/api/v1/investigations`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `OrderInvestigationRequest` | `ApiResponse<InvestigationResponse>` (201) | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE |
| PATCH | `/{id}/specimen-collected` | — | `ApiResponse<InvestigationResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| PATCH | `/{id}/in-progress` | — | `ApiResponse<InvestigationResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| PATCH | `/{id}/result` | `RecordInvestigationResultRequest` | `ApiResponse<InvestigationResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| PATCH | `/{id}/cancel` | `?reason=...` | `ApiResponse<InvestigationResponse>` | SUPER_ADMIN, DOCTOR |
| GET | `/{id}` | — | `ApiResponse<InvestigationResponse>` | Authenticated |
| GET | `/visit/{visitId}` | `?page=0&size=50` | `ApiResponse<Page<InvestigationResponse>>` | Authenticated |
| GET | `/visit/{visitId}/all` | — | `ApiResponse<List<InvestigationResponse>>` | Authenticated |
| GET | `/visit/{visitId}/type/{type}` | — | `ApiResponse<List<InvestigationResponse>>` | Authenticated |
| GET | `/visit/{visitId}/pending` | — | `ApiResponse<List<InvestigationResponse>>` | Authenticated |

**OrderInvestigationRequest:**
```json
{
  "visitId": "uuid",                                // @NotNull
  "investigationType": "LABORATORY",                // @NotNull — InvestigationType enum
  "testName": "Full Blood Count (FBC)",             // @NotBlank
  "orderedByName": "Dr. Gasana",
  "priority": "URGENT",
  "notes": "Check for infection markers"
}
```

**RecordInvestigationResultRequest:**
```json
{
  "investigationId": "uuid",                        // @NotNull
  "result": "WBC: 15.2 × 10⁹/L (High)",           // @NotBlank
  "isAbnormal": true,
  "isCritical": false,
  "notes": "Elevated WBC suggests infection"
}
```

**Investigation Status Flow:** `ORDERED` → `SPECIMEN_COLLECTED` → `IN_PROGRESS` → `RESULTED` (or `CANCELLED` from any state)

**InvestigationType enum:** `LABORATORY`, `RADIOLOGY`, `ECG`, `ULTRASOUND`, `CT_SCAN`, `MRI`, `XRAY`, `BLOOD_GAS`, `URINALYSIS`, `RAPID_TEST`, `POINT_OF_CARE`, `OTHER`

### 3.11 MEDICATIONS — `/api/v1/medications`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/` | `PrescribeMedicationRequest` | `ApiResponse<MedicationResponse>` (201) | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE |
| PATCH | `/{id}/administer` | `AdministerMedicationRequest` | `ApiResponse<MedicationResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| PATCH | `/{id}/countersign` | `CountersignMedicationRequest` | `ApiResponse<MedicationResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| PATCH | `/{id}/hold` | `?reason=...` | `ApiResponse<MedicationResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| PATCH | `/{id}/cancel` | `?reason=...` | `ApiResponse<MedicationResponse>` | SUPER_ADMIN, DOCTOR |
| PATCH | `/{id}/refuse` | `?reason=...` | `ApiResponse<MedicationResponse>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |
| GET | `/{id}` | — | `ApiResponse<MedicationResponse>` | Authenticated |
| GET | `/visit/{visitId}` | `?page=0&size=50` | `ApiResponse<Page<MedicationResponse>>` | Authenticated |
| GET | `/visit/{visitId}/all` | — | `ApiResponse<List<MedicationResponse>>` | Authenticated |

**PrescribeMedicationRequest:**
```json
{
  "visitId": "uuid",                                // @NotNull
  "drugName": "Paracetamol",                        // @NotBlank
  "dose": "1g",
  "route": "PO",                                    // @NotNull — MedicationRoute enum
  "frequency": "TDS (3x daily)",
  "prescribedByName": "Dr. Gasana",
  "notes": "For fever management"
}
```

**AdministerMedicationRequest:**
```json
{ "medicationId": "uuid", "administeredByName": "Nurse Uwimana", "notes": "Given with water" }
```

**CountersignMedicationRequest:**
```json
{ "medicationId": "uuid", "countersignedByName": "Sr. Nurse Habimana", "notes": "Verified" }
```

**Medication Status Flow:** `PRESCRIBED` → `ADMINISTERED` → (optionally countersigned). Also: `PRESCRIBED` → `HELD` / `REFUSED` / `CANCELLED`

**MedicationRoute enum:** `PO` (Oral), `IV` (Intravenous), `IM` (Intramuscular), `SC` (Subcutaneous), `SL` (Sublingual), `PR` (Per Rectum), `INH` (Inhalation), `NEB` (Nebuliser), `TOP` (Topical), `NASAL`, `OPHTHALMIC`, `OTIC` (Ear), `ETT` (Endotracheal), `IO` (Intraosseous), `OTHER`

### 3.12 IoT DEVICES — `/api/v1/iot`

#### Device Management

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/devices` | `RegisterDeviceRequest` | `DeviceResponse` (201) | ADMIN, DOCTOR, NURSE |
| GET | `/devices/{id}` | — | `DeviceResponse` | Authenticated |
| GET | `/devices/hospital/{hospitalId}` | `?page=0&size=20` | `Page<DeviceResponse>` | Authenticated |
| GET | `/devices/available/{hospitalId}` | — | `List<DeviceResponse>` | Authenticated |

**RegisterDeviceRequest:**
```json
{
  "serialNumber": "ESP32-MED-001",                  // @NotBlank
  "deviceName": "Bedside Monitor A1",               // @NotBlank
  "deviceType": "ESP32_MONITOR",                    // @NotNull — DeviceType enum
  "hospitalId": "uuid",                             // @NotNull
  "firmwareVersion": "2.1",
  "macAddress": "AA:BB:CC:DD:EE:FF",
  "location": "ED Bay 3",
  "heartbeatTimeoutSeconds": 30,
  "dataIntervalSeconds": 5,
  "notes": "Primary triage monitor"
}
```

**DeviceType enum:** `ESP32_MONITOR`, `PULSE_OXIMETER`, `ECG_MONITOR`, `BP_MONITOR`, `TEMPERATURE_PROBE`, `GLUCOMETER`, `AMBULANCE_MONITOR`, `OTHER`

**DeviceStatus enum:** `REGISTERED`, `ONLINE`, `OFFLINE`, `MONITORING`, `ERROR`, `DECOMMISSIONED`

**DeviceResponse:** All fields + `id`, `status`, `lastHeartbeatAt`, `lastDataAt`, `batteryLevel`, `wifiRssi`, `ipAddress`, `apiKey` (returned only once at registration), `activeVisitId`, `createdAt`, `updatedAt`

#### Monitoring Sessions

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/monitoring/start` | `StartMonitoringRequest` | `DeviceSessionResponse` (201) | ADMIN, DOCTOR, NURSE |
| POST | `/monitoring/stop/{sessionId}` | `?endedByName=...&reason=...` | `DeviceSessionResponse` | ADMIN, DOCTOR, NURSE |
| GET | `/monitoring/active/{hospitalId}` | — | `List<DeviceSessionResponse>` | Authenticated |
| GET | `/monitoring/session/{sessionId}` | — | `DeviceSessionResponse` | Authenticated |
| GET | `/monitoring/history/{visitId}` | `?page=0&size=20` | `Page<DeviceSessionResponse>` | Authenticated |

**StartMonitoringRequest:**
```json
{
  "deviceId": "uuid",                               // @NotNull
  "visitId": "uuid",                                // @NotNull
  "startedByName": "Nurse Uwimana"
}
```

#### Vital Stream (real-time data)

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| GET | `/stream/latest/{visitId}` | — | `VitalStreamResponse` | Authenticated |
| GET | `/stream/recent/{visitId}` | `?count=60` | `List<VitalStreamResponse>` | Authenticated |
| GET | `/stream/history/{visitId}` | `?page=0&size=20` | `Page<VitalStreamResponse>` | Authenticated |
| GET | `/stream/session/{sessionId}` | `?page=0&size=20` | `Page<VitalStreamResponse>` | Authenticated |

**VitalStreamResponse:**
```json
{
  "id": "uuid",
  "visitId": "uuid",
  "deviceId": "ESP32-MED-001",
  "sessionId": "uuid",
  "capturedAt": "2026-03-05T10:30:01Z",
  "receivedAt": "2026-03-05T10:30:01.123Z",
  "heartRate": 78,
  "spo2": 98,
  "respiratoryRate": 16,
  "temperature": 36.8,
  "systolicBp": null,
  "diastolicBp": null,
  "bloodGlucose": null,
  "ecgRhythm": "NORMAL_SINUS",
  "ecgQrsDuration": 90,
  "signalQuality": "GOOD",
  "spo2PerfusionIndex": 4.2,
  "isValidated": true,
  "rejectionReason": null,
  "batteryLevel": 85,
  "wifiRssi": -45,
  "sequenceNumber": 12345
}
```

#### Device Ingest (device-to-server, NOT used by frontend)

These 2 endpoints use **API key auth** (header `X-Device-API-Key`), not JWT:

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| POST | `/stream/ingest` | `DeviceVitalPayload` | `DeviceAckResponse` | API Key |
| POST | `/stream/heartbeat` | — | `DeviceAckResponse` | API Key |

### 3.13 ALERTS — `/api/v1/alerts`

| Method | Path | Body/Params | Response | Auth |
|--------|------|-------------|----------|------|
| GET | `/visit/{visitId}` | `Pageable` | `ApiResponse<Page<ClinicalAlert>>` | Authenticated |
| GET | `/hospital/{hospitalId}/unacknowledged` | `Pageable` | `ApiResponse<Page<ClinicalAlert>>` | Authenticated |
| GET | `/hospital/{hospitalId}/critical` | `Pageable` | `ApiResponse<Page<ClinicalAlert>>` | Authenticated |
| PATCH | `/{alertId}/acknowledge` | — | `ApiResponse<Void>` | SUPER_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE |

**AlertType enum:** `TEWS_CRITICAL`, `TEWS_ESCALATION`, `VITAL_SIGN_ABNORMAL`, `RETRIAGE_REQUIRED`, `WAITING_TIME_EXCEEDED`, `DETERIORATION_DETECTED`, `SEPSIS_SCREENING`, `PEDIATRIC_SAFETY`, `REASSESSMENT_DUE`, `CRITICAL_LAB_RESULT`, `IOT_DEVICE_DISCONNECTED`, `IOT_DEVICE_LOW_BATTERY`, `IOT_SIGNAL_QUALITY_DEGRADED`, `IOT_AUTO_RETRIAGE`

**AlertSeverity enum:** `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`

---

## 4. WEBSOCKET (REAL-TIME)

**Endpoint:** `ws://localhost:8080/ws/smarttriage` (STOMP protocol)

**Topics the frontend should subscribe to:**

| Topic | Payload | Description |
|-------|---------|-------------|
| `/topic/vitals/{visitId}` | `VitalStreamResponse` | Real-time vital readings for a patient |
| `/topic/alerts/{hospitalId}` | Alert object | New clinical alerts for a hospital |
| `/topic/devices/{hospitalId}` | Device status change | Device online/offline/error events |
| `/topic/triage/{visitId}` | Triage change event | Triage category change for a patient |

**Frontend integration:** Use SockJS + @stomp/stompjs:
```javascript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const client = new Client({
  webSocketFactory: () => new SockJS('http://localhost:8080/ws/smarttriage'),
  onConnect: () => {
    client.subscribe('/topic/vitals/' + visitId, (message) => {
      const vital = JSON.parse(message.body);
      // Update real-time vital display
    });
    client.subscribe('/topic/alerts/' + hospitalId, (message) => {
      const alert = JSON.parse(message.body);
      // Show alert notification
    });
  }
});
client.activate();
```

---

## 5. AUTHENTICATION FLOW FOR FRONTEND

### Login Flow:
1. POST `/api/v1/auth/login` with `{ email, password }`
2. Receive `AuthResponse` with `accessToken`, `refreshToken`, user details
3. Store `accessToken` in memory (NOT localStorage for security), `refreshToken` in httpOnly cookie or secure storage
4. Send `Authorization: Bearer <accessToken>` header on every subsequent request
5. When access token expires (401 response), POST `/api/v1/auth/refresh` with the refresh token
6. If refresh fails → redirect to login

### Role-Based UI:
The frontend must show/hide features based on the user's `role` from `AuthResponse`:

| Role | Can See/Do |
|------|-----------|
| SUPER_ADMIN | Everything — hospital management, user management, all clinical features |
| HOSPITAL_ADMIN | User management for their hospital, all clinical views |
| DOCTOR | Full clinical access — triage, diagnoses, investigations, medications, clinical notes |
| TRIAGE_NURSE | Triage, vitals, clinical notes, some medication, some investigations |
| NURSE | Vitals, clinical notes, medication administration, investigation specimen collection |
| REGISTRAR | Patient registration, visit creation, view-only for clinical |
| PARAMEDIC | Vitals recording, view-only for most clinical |
| LAB_TECHNICIAN | Investigation results, view-only for clinical |
| READ_ONLY | View everything, modify nothing |

### Seeded Credentials for Development:
- **Email:** `admin@smarttriage.com`
- **Password:** `SmartTriage@2026`
- **Role:** SUPER_ADMIN
- **Hospital:** SmartTriage Central (ID: `a0000000-0000-0000-0000-000000000001`)

---

## 6. COMPLETE ENUM REFERENCE

All enums the frontend needs to handle (for dropdowns, filters, badges, etc.):

```
Gender: MALE, FEMALE, OTHER, UNKNOWN
Role: SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE, REGISTRAR, PARAMEDIC, LAB_TECHNICIAN, READ_ONLY
ArrivalMode: WALK_IN, AMBULANCE, REFERRAL, POLICE, HELICOPTER, OTHER
VisitStatus: REGISTERED, AWAITING_TRIAGE, TRIAGED, AWAITING_ASSESSMENT, UNDER_ASSESSMENT, UNDER_TREATMENT, UNDER_OBSERVATION, PENDING_DISPOSITION, DISCHARGED, ADMITTED, TRANSFERRED, ICU_ADMITTED, LEFT_WITHOUT_BEING_SEEN, DECEASED
TriageCategory: RED, ORANGE, YELLOW, GREEN, BLUE
DispositionType: DISCHARGED_HOME, ADMITTED_TO_WARD, ICU_ADMISSION, TRANSFERRED, LEFT_AGAINST_MEDICAL_ADVICE, LEFT_WITHOUT_BEING_SEEN, DECEASED
AvpuScore: ALERT, CONFUSED, VERBAL, PAIN, UNRESPONSIVE
VitalSource: MANUAL_ENTRY, IOT_DEVICE, AMBULANCE_MONITOR, IMPORTED
MobilityStatus: WALKING, WITH_HELP, STRETCHER
TraumaStatus: NO_TRAUMA, TRAUMA
NoteType: PHYSICAL_FINDINGS, PROGRESS_NOTE, NURSING_NOTE, DOCTOR_NOTE, TRIAGE_NOTE, HISTORY_OF_PRESENTING_COMPLAINT, PAST_MEDICAL_HISTORY, SOCIAL_HISTORY, FAMILY_HISTORY, REVIEW_OF_SYSTEMS, ALLERGIES, CURRENT_MEDICATIONS, TREATMENT_PLAN, DISCHARGE_SUMMARY, HANDOVER, OTHER
DiagnosisType: PROVISIONAL, CONFIRMED, DIFFERENTIAL, WORKING
InvestigationType: LABORATORY, RADIOLOGY, ECG, ULTRASOUND, CT_SCAN, MRI, XRAY, BLOOD_GAS, URINALYSIS, RAPID_TEST, POINT_OF_CARE, OTHER
InvestigationStatus: ORDERED, SPECIMEN_COLLECTED, IN_PROGRESS, RESULTED, CANCELLED
MedicationRoute: PO, IV, IM, SC, SL, PR, INH, NEB, TOP, NASAL, OPHTHALMIC, OTIC, ETT, IO, OTHER
MedicationStatus: PRESCRIBED, ADMINISTERED, HELD, REFUSED, CANCELLED
DeviceType: ESP32_MONITOR, PULSE_OXIMETER, ECG_MONITOR, BP_MONITOR, TEMPERATURE_PROBE, GLUCOMETER, AMBULANCE_MONITOR, OTHER
DeviceStatus: REGISTERED, ONLINE, OFFLINE, MONITORING, ERROR, DECOMMISSIONED
SignalQuality: GOOD, ACCEPTABLE, POOR, INVALID, UNKNOWN
AlertSeverity: CRITICAL, HIGH, MEDIUM, LOW, INFO
AlertType: TEWS_CRITICAL, TEWS_ESCALATION, VITAL_SIGN_ABNORMAL, RETRIAGE_REQUIRED, WAITING_TIME_EXCEEDED, DETERIORATION_DETECTED, SEPSIS_SCREENING, PEDIATRIC_SAFETY, REASSESSMENT_DUE, CRITICAL_LAB_RESULT, IOT_DEVICE_DISCONNECTED, IOT_DEVICE_LOW_BATTERY, IOT_SIGNAL_QUALITY_DEGRADED, IOT_AUTO_RETRIAGE
```

---

## 7. INTEGRATION INSTRUCTIONS

### 7.1 How to Handle Frontend ↔ Backend Mismatches

**RULE: The backend is the source of truth.** The backend has been carefully designed with clinical correctness, security, and data integrity. The frontend must adapt to the backend's API contracts.

**RULE: Preserve existing frontend quality.** The frontend already has premium, polished implementations for dashboards, patient registration, patient registry, triage queue, and constant monitoring. DO NOT redesign, restructure, or restyle these. Only connect them to real API data and add what's missing.

**Scenario A — Frontend has a feature/field NOT in the backend:**
- If it's purely a UI concern (e.g., dark mode toggle, sidebar collapse state) → keep it frontend-only, no backend changes needed
- If it requires persisted data (e.g., a "patient photo" field, a "notes" field on a screen that the backend doesn't support) → **do NOT add random fields to the backend**. Instead, check if an existing backend field can serve the purpose (e.g., `notes` fields exist on most entities). If truly needed and clinically important, document it as a "Backend Enhancement Needed" item but DO NOT modify the backend yourself.

**Scenario B — Backend has a feature/field that the frontend doesn't show:**
- If it's a critical clinical feature (triage, vitals, alerts, medications) → **the frontend MUST implement it**. The backend was designed with clinical requirements; every field exists for a reason.
- If it's an admin feature (hospital management, user creation) → implement it in an admin section
- If it's IoT device management → implement it in a device management dashboard

**Scenario C — Different field names/structures:**
- Map them in a frontend API service layer. Never rename backend response fields — create a mapping/transform function instead.

**Scenario D — Frontend feature already exists and works:**
- **DO NOT TOUCH IT.** If a page/component is already implemented and functional (dashboards, patient registration, patient list, triage queue, real-time monitoring), only wire it to the real backend API. Do not change its layout, design, or component structure. New pages must replicate the same design language.

### 7.2 Required Frontend Pages/Features (based on backend capabilities)

The frontend MUST have these screens to fully utilize the backend. Items marked ✅ are **ALREADY IMPLEMENTED** — do not redesign them, only wire to real API data. Items marked 🔧 **NEED IMPLEMENTATION**.

1. ✅ **Login Page** — ALREADY EXISTS. Wire to `/api/v1/auth/login`, store JWT tokens, implement auto-refresh.
2. ✅ **Dashboard** — ALREADY EXISTS & PERFECT. Wire to real data:
   - Active visit count by triage category (color-coded: RED/ORANGE/YELLOW/GREEN)
   - Unacknowledged alert count
   - Active IoT monitoring sessions
   - Quick stats
3. ✅ **Patient Registration** — ALREADY EXISTS & PERFECT. Wire to `/api/v1/patients` POST endpoint.
4. ✅ **Patient List (Patient Registry)** — ALREADY EXISTS & PERFECT. Wire to `/api/v1/patients/*` search/list endpoints.
5. 🔧 **Patient Detail** — demographics, visit history, medical info → `/api/v1/patients/{id}`, `/api/v1/visits/patient/{patientId}`
6. ✅ **Triage Queue / Active Visits** — ALREADY EXISTS & PERFECT. Wire to `/api/v1/visits/hospital/{id}/active`
7. 🔧 **Visit Detail** — the main clinical workspace with tabs/sections for:
   - **Vitals** — record manual vitals, view history chart/table
   - **Triage Form** — the complete SATS form (adult + pediatric) with all ~70 fields
   - **Triage History** — past triage records for this visit
   - **Clinical Notes** — create/view notes by type
   - **Diagnoses** — add/manage diagnoses
   - **Investigations** — order, track status, record results
   - **Medications (MAR)** — prescribe, administer, countersign
   - **Real-Time Monitor** — live vital signs from IoT device (WebSocket)
   - **Alerts** — clinical alerts for this visit
8. ✅ **Constant Monitoring (Real-Time Vitals)** — ALREADY EXISTS & PERFECT. Wire to WebSocket STOMP topics + `/api/v1/iot/*` REST endpoints.
9. 🔧 **Alert Dashboard** — hospital-wide unacknowledged and critical alerts → `/api/v1/alerts/*`
10. 🔧 **IoT Device Management** — register devices, view status, start/stop monitoring → `/api/v1/iot/*`
11. 🔧 **Admin: Hospital Management** (SUPER_ADMIN only) — CRUD hospitals
12. 🔧 **Admin: User Management** (SUPER_ADMIN, HOSPITAL_ADMIN) — CRUD staff users

### 7.3 API Service Layer Pattern

Create a centralized API service with:
- Base URL configuration (`http://localhost:8080`)
- JWT token injection via Axios/fetch interceptor
- Automatic token refresh on 401
- Response unwrapping (extract `.data` from `ApiResponse` wrapper)
- Error handling (show `.message` from error responses)
- Pagination helper (handle Spring Page structure)

Example pattern:
```typescript
// api/client.ts
const API_BASE = 'http://localhost:8080/api/v1';

async function apiRequest<T>(path: string, options?: RequestInit): Promise<T> {
  const token = getAccessToken();
  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
      ...options?.headers,
    },
  });

  if (response.status === 401) {
    // Try refresh token
    const refreshed = await refreshAccessToken();
    if (refreshed) return apiRequest<T>(path, options); // Retry
    throw new Error('Session expired');
  }

  const apiResponse = await response.json(); // { success, message, data, timestamp }
  if (!apiResponse.success) throw new Error(apiResponse.message);
  return apiResponse.data;
}

// api/patients.ts
export const patientApi = {
  create: (data: CreatePatientRequest) =>
    apiRequest<PatientResponse>('/patients', { method: 'POST', body: JSON.stringify(data) }),

  getById: (id: string) =>
    apiRequest<PatientResponse>(`/patients/${id}`),

  search: (hospitalId: string, query: string, page = 0) =>
    apiRequest<Page<PatientResponse>>(`/patients/hospital/${hospitalId}/search?query=${query}&page=${page}`),
};
```

### 7.4 CORS Configuration

The backend currently allows all origins for WebSocket (`setAllowedOriginPatterns("*")`). For REST endpoints, you may need to add a CORS configuration. If you get CORS errors, the frontend should proxy API requests in development (e.g., Next.js `rewrites` in `next.config.js`, or Vite proxy config).

### 7.5 Date/Time Handling

- All timestamps from the backend are **ISO 8601 UTC** (`Instant` → `"2026-03-05T10:30:00Z"`)
- Dates are `LocalDate` → `"2026-03-05"` (for dateOfBirth)
- Frontend should display in local timezone but send UTC to backend
- Use a date library (date-fns, dayjs) for formatting

---

## 8. CRITICAL CLINICAL FEATURES THAT MUST WORK CORRECTLY

These are clinically critical — if the frontend gets these wrong, patient safety is at risk:

1. **Triage color-coding** — RED/ORANGE/YELLOW/GREEN must be visually prominent and correct. RED = immediate danger. This is the #1 most important visual element.

2. **TEWS score display** — Show the numeric TEWS score (0-17) alongside the triage category. Higher = worse.

3. **Alert notifications** — Critical and high-severity alerts MUST be prominent (sound, visual, cannot be easily dismissed). These could indicate a patient is deteriorating.

4. **Visit queue ordering** — Active visits should be sorted by triage severity (RED first), then by waiting time within each category.

5. **Pediatric detection** — The system auto-detects pediatric patients (age < 12) and shows the child-specific triage form with additional emergency signs (cyanosis, dehydration, cold hands). The frontend must conditionally render child-specific fields.

6. **Medication double-check** — The countersign workflow exists for patient safety. The administered-by and countersigned-by should be DIFFERENT people.

7. **Real-time vitals** — When an IoT device is monitoring a patient, the frontend should show live-updating vital signs with trend indicators. Abnormal values should be highlighted.

8. **Investigation status tracking** — Investigations go through a workflow (ORDERED → SPECIMEN_COLLECTED → IN_PROGRESS → RESULTED). Each step must be clearly visible.

---

## 9. WHAT TO DO FIRST

> **REMEMBER:** Dashboards, Patient Registration, Patient Registry, Triage Queue, and Constant Monitoring are ALREADY COMPLETE and PREMIUM. Do NOT redesign them. Only wire them to real backend data and build what's missing. All new UI must match the existing design language exactly.

1. **Read the frontend codebase** thoroughly — understand its current routing, state management, component structure, design system, and what API calls it already makes (if any). **Pay close attention to the existing premium UI patterns — you must replicate them exactly for any new pages.**
2. **Catalog what exists vs. what's missing** — map each existing frontend page to the backend endpoints it needs. Identify which pages are already built (see ✅ list in Section 7.2) and which need to be created (see 🔧 list).
3. **Build the API service layer** — centralized HTTP client with auth, error handling, response unwrapping (see pattern in Section 7.3)
4. **Connect auth first** — wire login page → JWT token storage → protected routes → role-based rendering
5. **Wire existing pages to real data** — connect the ALREADY-BUILT pages (dashboard, patient registration, patient list, triage queue, constant monitoring) to their backend endpoints. Replace any mock/static/hardcoded data with live API calls. DO NOT change their UI.
6. **Build the Visit Detail page** — this is the biggest missing piece. Create the full clinical workspace with tabs for vitals, triage, clinical notes, diagnoses, investigations, medications, real-time monitor, alerts. **Match the existing premium UI exactly.**
7. **Build remaining missing pages** — Alert Dashboard, IoT Device Management, Admin pages. All must match existing aesthetics.
8. **Add real-time features** — WebSocket connection for live vitals and alerts (if not already fully wired)
9. **Test the full workflow** — register patient → create visit → record vitals → perform triage → clinical documentation → disposition

---

## 10. DEVELOPMENT SETUP

**Backend is already running at:**
- URL: `http://localhost:8080`
- Database: PostgreSQL at `localhost:5432/smarttriage_dev` (user: `postgres`, password: `password`)
- WebSocket: `ws://localhost:8080/ws/smarttriage`

**Login credentials:**
- Email: `admin@smarttriage.com`
- Password: `SmartTriage@2026`

**Key API test (verify backend is running):**
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@smarttriage.com","password":"SmartTriage@2026"}'
```

This should return an `AuthResponse` with tokens. If it works, the backend is ready.

---

**Remember: This is a real medical system. Every clinical decision, every data flow, every UI element must be accurate. When in doubt about clinical correctness, preserve what the backend implements — it was designed with clinical guidance.**

**Remember: The existing frontend UI is PREMIUM and COMPLETE for dashboards, patient registration, patient registry, triage queue, and constant monitoring. DO NOT redesign these — only wire them to real data. Any NEW pages must match the existing design quality exactly.**
