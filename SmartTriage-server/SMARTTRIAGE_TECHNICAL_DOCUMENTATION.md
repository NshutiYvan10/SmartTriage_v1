# SmartTriage Server — Technical Documentation
**Date:** February 26, 2026  
**Version:** 0.0.1-SNAPSHOT  
**Status:** Running on port 8080 ✅

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Technology Stack](#2-technology-stack)
3. [Project Structure](#3-project-structure)
4. [Core Architecture](#4-core-architecture)
5. [Database Design — Flyway Migrations](#5-database-design--flyway-migrations)
6. [Module Reference](#6-module-reference)
   - 6.1 [Auth Module](#61-auth-module)
   - 6.2 [Hospital Module](#62-hospital-module)
   - 6.3 [User Module](#63-user-module)
   - 6.4 [Patient Module](#64-patient-module)
   - 6.5 [Visit Module](#65-visit-module)
   - 6.6 [Triage Module](#66-triage-module)
   - 6.7 [Vital Signs Module](#67-vital-signs-module)
   - 6.8 [Alert Module](#68-alert-module)
   - 6.9 [Medication Module](#69-medication-module)
   - 6.10 [Clinical Documentation Module](#610-clinical-documentation-module)
   - 6.11 [IoT Integration Module](#611-iot-integration-module)
7. [Security Architecture](#7-security-architecture)
8. [Real-Time Communication — WebSocket](#8-real-time-communication--websocket)
9. [API Reference](#9-api-reference)
10. [Enums Reference](#10-enums-reference)
11. [Configuration Reference](#11-configuration-reference)
12. [File Inventory](#12-file-inventory)

---

## 1. System Overview

SmartTriage is a **production-grade, AI-assisted Emergency Department and ICU clinical workflow backend** built for Rwandan hospitals. It digitises the Rwanda National Standard Triage Protocol, implements continuous IoT-based patient monitoring, and provides a complete clinical data management platform.

### Core Capabilities

| Capability | Description |
|---|---|
| **Adult Triage** | Exact Rwanda National Standard Adult Triage Form — TEWS scoring, decision engine |
| **Pediatric Triage** | Rwanda Child Triage Form (ages 3–12) — child-specific thresholds and emergency signs |
| **IoT Monitoring** | ESP32 device streaming vitals every 1–5 seconds, real-time to dashboards |
| **AI Auto-Retriage** | Continuous deterioration detection triggers system retriage with 10-min cooldown |
| **Medication MAR** | Full prescribe → administer → countersign workflow with audit trail |
| **Clinical Documentation** | Diagnosis, Investigation orders, Clinical Notes |
| **Real-Time Alerts** | WebSocket push to frontend dashboards for vitals, alerts, device events |
| **Fail-Safe Monitoring** | Heartbeat scheduler detects disconnected devices, raises CRITICAL alerts |

---

## 2. Technology Stack

| Component | Technology | Version |
|---|---|---|
| Framework | Spring Boot | 4.0.3 |
| Language | Java | 21 |
| Spring Core | Spring Framework | 7.0.5 |
| ORM | Hibernate / Spring Data JPA | 7.2.4 |
| Database | PostgreSQL | 14.21 |
| Migrations | Flyway | (Spring Boot managed) |
| Security | Spring Security | (Spring Boot managed) |
| JWT | jjwt | 0.12.6 |
| Password Hashing | BCrypt | strength 12 |
| WebSocket | Spring WebSocket (STOMP) | (Spring Boot managed) |
| JSON | Jackson 3.x | (Spring Boot managed) |
| Boilerplate | Lombok | (Spring Boot managed) |
| Build | Maven | (mvnw wrapper) |
| App Server | Tomcat | 11.0.18 (embedded) |

---

## 3. Project Structure

```
SmartTriage-server/
├── pom.xml
├── src/main/
│   ├── java/com/smartTriage/smartTriage_server/
│   │   ├── SmartTriageServerApplication.java     ← @SpringBootApplication + @EnableScheduling
│   │   ├── config/
│   │   │   ├── JpaAuditingConfig.java
│   │   │   ├── PasswordEncoderConfig.java
│   │   │   ├── SecurityConfig.java
│   │   │   └── WebSocketConfig.java              ← NEW (IoT session)
│   │   ├── security/
│   │   │   ├── JwtAuthenticationEntryPoint.java
│   │   │   ├── JwtAuthenticationFilter.java
│   │   │   └── JwtService.java
│   │   ├── common/
│   │   │   ├── dto/ApiResponse.java
│   │   │   ├── entity/BaseEntity.java
│   │   │   ├── enums/  (25 enums)
│   │   │   └── exception/  (5 exception classes)
│   │   └── module/
│   │       ├── alert/
│   │       ├── auth/
│   │       ├── clinical/
│   │       ├── hospital/
│   │       ├── iot/                              ← NEW (IoT session)
│   │       ├── medication/
│   │       ├── patient/
│   │       ├── triage/
│   │       ├── user/
│   │       ├── visit/
│   │       └── vital/
│   └── resources/
│       ├── application.properties
│       ├── application-dev.properties
│       ├── application-staging.properties
│       ├── application-prod.properties
│       └── db/migration/
│           ├── V1__initial_schema.sql
│           ├── V2__seed_data.sql
│           ├── V3__rwanda_adult_triage_form_compliance.sql
│           ├── V4__rwanda_child_triage_form_support.sql
│           ├── V5__medication_and_clinical_documentation.sql
│           └── V6__iot_devices_sessions_streams.sql  ← NEW (IoT session)
```

**Total Java source files:** 143  
**Total migration lines:** 761 SQL lines across 6 migrations

---

## 4. Core Architecture

### BaseEntity

Every domain entity extends `BaseEntity`, which provides:

| Field | Type | Description |
|---|---|---|
| `id` | `UUID` | Auto-generated primary key (`gen_random_uuid()`) |
| `createdAt` | `Instant` | Set on creation by Spring Data Auditing |
| `updatedAt` | `Instant` | Updated on every save |
| `createdBy` | `String` | Username from security context |
| `lastModifiedBy` | `String` | Username from security context |
| `isActive` | `boolean` | Soft-delete flag (default `true`) |
| `version` | `Long` | Optimistic locking (Hibernate `@Version`) |

**Soft Delete:** `softDelete()` sets `isActive = false`. No data is ever physically deleted. All queries filter on `isActive = true`.

### Module Pattern

Every module follows the same layered structure:

```
entity/ → repository/ → service/ → controller/ → dto/ → mapper/
```

All services are `@Transactional(readOnly = true)` by default; write operations are annotated `@Transactional` individually.

---

## 5. Database Design — Flyway Migrations

### V1 — Initial Schema
Core tables: `hospitals`, `users`, `patients`, `visits`, `vital_signs`, `triage_records`, `clinical_alerts`.

### V2 — Seed Data
Default hospital, admin/doctor/nurse users (BCrypt-hashed passwords).

### V3 — Rwanda Adult Triage Form Compliance
Added all adult triage form fields to `triage_records`: emergency signs, very-urgent signs, urgent signs, TEWS components, mobility, AVPU, trauma status, decision path, retriage tracking.

### V4 — Rwanda Child Triage Form Support
Added child-specific fields: `is_child_form`, central cyanosis, absent/low pulse, cold extremities composite signs, severe dehydration indicators, child weight/height, `is_system_triggered` flag for AI-driven retriages.

### V5 — Medication & Clinical Documentation
New tables: `medication_administrations`, `diagnoses`, `investigations`, `clinical_notes`.

### V6 — IoT Integration (NEW — this session)
New tables with full audit trail and performance indexes:

**`iot_devices`**
```sql
serial_number     VARCHAR(100) UNIQUE   -- hardware serial
device_name       VARCHAR(100)
device_type       VARCHAR(30)           -- DeviceType enum
hospital_id       UUID FK → hospitals
api_key           VARCHAR(255) UNIQUE   -- pre-shared auth credential
status            VARCHAR(20)           -- DeviceStatus enum
firmware_version  VARCHAR(30)
last_heartbeat_at TIMESTAMPTZ
last_data_at      TIMESTAMPTZ
battery_level     INTEGER
wifi_rssi         INTEGER
ip_address        VARCHAR(45)
mac_address       VARCHAR(17)
location          VARCHAR(100)
heartbeat_timeout_seconds INTEGER DEFAULT 30
data_interval_seconds     INTEGER DEFAULT 5
```

**`device_sessions`**
```sql
device_id         UUID FK → iot_devices
visit_id          UUID FK → visits
started_at        TIMESTAMPTZ
ended_at          TIMESTAMPTZ
session_active    BOOLEAN DEFAULT true
started_by_name   VARCHAR(255)
ended_by_name     VARCHAR(255)
end_reason        VARCHAR(255)
total_readings    BIGINT DEFAULT 0
rejected_readings BIGINT DEFAULT 0
alerts_generated  INTEGER DEFAULT 0
retriages_triggered INTEGER DEFAULT 0
```

**`vital_streams`**
```sql
visit_id          UUID FK → visits
device_id         VARCHAR(100)          -- serial number (denormalised for perf)
session_id        UUID                  -- FK to device_sessions
captured_at       TIMESTAMPTZ           -- device-side timestamp
received_at       TIMESTAMPTZ           -- server-side timestamp
heart_rate        INTEGER
spo2              INTEGER
respiratory_rate  INTEGER
temperature       DOUBLE PRECISION
systolic_bp       INTEGER
diastolic_bp      INTEGER
blood_glucose     DOUBLE PRECISION
ecg_waveform      TEXT                  -- comma-separated ADC values
ecg_rhythm        VARCHAR(30)
ecg_qrs_duration  INTEGER
signal_quality    VARCHAR(15)           -- SignalQuality enum
spo2_perfusion_index DOUBLE PRECISION
is_validated      BOOLEAN DEFAULT false
rejection_reason  VARCHAR(255)
battery_level     INTEGER
wifi_rssi         INTEGER
sequence_number   BIGINT                -- for gap detection
```

Performance indexes: `(visit_id, captured_at)`, `(visit_id, is_validated, is_active, captured_at)` composite for trend queries.

---

## 6. Module Reference

### 6.1 Auth Module

**Package:** `module/auth`

| File | Role |
|---|---|
| `AuthController` | `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh` |
| `AuthService` | Authenticates credentials, issues JWT access + refresh tokens |
| `LoginRequest` | `{ email, password }` |
| `AuthResponse` | `{ accessToken, refreshToken, tokenType, expiresIn, user }` |

**JWT Configuration:**
- Access token: 15 minutes (900,000 ms)
- Refresh token: 24 hours (86,400,000 ms)
- Algorithm: HMAC-SHA256 with configurable secret

---

### 6.2 Hospital Module

**Package:** `module/hospital`

| File | Role |
|---|---|
| `Hospital` entity | `hospitals` table — name, code, address, phone, type |
| `HospitalController` | CRUD endpoints at `/api/v1/hospitals` |
| `HospitalService` | `findHospitalOrThrow(UUID)` used across modules |

---

### 6.3 User Module

**Package:** `module/user`

| File | Role |
|---|---|
| `User` entity | `users` table — implements `UserDetails` for Spring Security |
| `UserController` | CRUD at `/api/v1/users` |
| `UserService` | Implements `UserDetailsService`, BCrypt password handling |

**Roles:** `ADMIN`, `DOCTOR`, `NURSE`, `RECEPTIONIST`, `LAB_TECHNICIAN`

---

### 6.4 Patient Module

**Package:** `module/patient`

| File | Role |
|---|---|
| `Patient` entity | `patients` table — demographic data, NID, DOB, gender |
| `PatientController` | CRUD + search at `/api/v1/patients` |
| `PatientService` | Duplicate NID detection, patient lookup |

---

### 6.5 Visit Module

**Package:** `module/visit`

Key fields on `Visit` entity:

| Field | Type | Description |
|---|---|---|
| `visitNumber` | String | Auto-generated unique visit ID |
| `isPediatric` | boolean | Routes to child vs adult triage engines |
| `currentTriageCategory` | `TriageCategory` | Live triage status |
| `currentTewsScore` | Integer | Latest TEWS score |
| `triageTime` | Instant | Time of last triage |
| `retriageCount` | int | Number of retriages performed |
| `status` | `VisitStatus` | REGISTERED / TRIAGED / IN_PROGRESS / DISCHARGED |

---

### 6.6 Triage Module

**Package:** `module/triage`

The most clinically critical module. Implements the Rwanda National Standard Triage Protocol.

#### Engines

**`TewsCalculator`** — Adult TEWS (Triage Early Warning Score)

Implements the exact Rwanda Adult Triage Form scoring grid:

| Parameter | Score 3 | Score 2 | Score 1 | Score 0 | Score 1 | Score 2 | Score 3 |
|---|---|---|---|---|---|---|---|
| Mobility | — | — | — | Walking | Wheelchair/Help | Stretcher | — |
| RR | — | — | < 9 | 9–14 | 15–20 | 21–29 | > 29 |
| Pulse | — | < 41 | 41–50 | 51–100 | 101–110 | 111–129 | > 129 |
| SBP | — | < 71 | 71–80 | 81–100 | 101–199 | — | > 199 |
| Temp | — | < 35°C | — | 35–38.4 | — | — | ≥ 38.4 |
| AVPU | — | — | Confused | Alert | Voice | Pain | Unresponsive |
| Trauma | — | — | — | No | Yes | — | — |

**`PediatricTewsCalculator`** — Child TEWS (ages 3–12)  
Child-specific thresholds — different HR/RR ranges, no SBP scoring for children.

**`RwandaTriageDecisionEngine`** — Adult decision flowchart  
Returns `TriageDecisionResult(category, decisionPath)`.

**`RwandaPediatricTriageDecisionEngine`** — Child decision flowchart  
Child-specific emergency signs: central cyanosis, absent pulse, cold extremities composite, severe dehydration.

#### Triage Categories

| Category | Description | Max Wait | Severity |
|---|---|---|---|
| `RED` | Immediate | 0 min | 4 |
| `ORANGE` | Very Urgent | 10 min | 3 |
| `YELLOW` | Urgent | 30 min | 2 |
| `GREEN` | Routine | 60 min | 1 |
| `BLUE` | Dead on Arrival | — | 0 |

#### Auto-Retriage (IoT Integration)

`TriageRecord` has `isSystemTriggered = true` when retriage is triggered by the `ContinuousMonitoringEngine`. The `previousCategory` field records what the category was before the retriage.

#### API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/triage` | Perform initial triage or manual retriage |
| `GET` | `/api/v1/triage/visit/{visitId}` | Full triage history |
| `GET` | `/api/v1/triage/visit/{visitId}/latest` | Latest triage record |

---

### 6.7 Vital Signs Module

**Package:** `module/vital`

`VitalSigns` is the **clinical snapshot** table — low-frequency (minutes apart), validated, used for TEWS calculation.

Key fields: `respiratoryRate`, `heartRate`, `systolicBp`, `diastolicBp`, `temperature`, `spo2`, `avpu` (`AvpuScore`), `bloodGlucose`, `painScore`, `gcsScore`, `source` (`VitalSource`), `deviceId`.

`VitalSource` enum: `MANUAL_ENTRY`, `IOT_DEVICE`, `AMBULANCE_TELEMETRY`, `NURSE_STATION`.

#### API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/vitals` | Record vital signs |
| `GET` | `/api/v1/vitals/visit/{visitId}` | Vital history (paginated) |
| `GET` | `/api/v1/vitals/visit/{visitId}/latest` | Latest vitals |

---

### 6.8 Alert Module

**Package:** `module/alert`

`ClinicalAlert` entity fields: `visit` (FK), `alertType`, `severity`, `title`, `message`, `isAcknowledged`, `acknowledgedBy`, `acknowledgedAt`, `autoGenerated`.

#### Alert Types

| Type | Trigger |
|---|---|
| `TEWS_CRITICAL` | RED triage assigned |
| `TEWS_ESCALATION` | Category escalated on retriage |
| `VITAL_SIGN_ABNORMAL` | Manual vital entry triggers abnormal check |
| `DETERIORATION_DETECTED` | IoT monitoring engine detects deterioration |
| `RETRIAGE_REQUIRED` | System recommends retriage |
| `WAITING_TIME_EXCEEDED` | Patient waiting beyond category limit |
| `SEPSIS_SCREENING` | Sepsis pattern detected |
| `PEDIATRIC_SAFETY` | Child-specific safety trigger |
| `REASSESSMENT_DUE` | Time-based reassessment reminder |
| `CRITICAL_LAB_RESULT` | Critical investigation result |
| `IOT_DEVICE_DISCONNECTED` | Device missed heartbeat while monitoring |
| `IOT_DEVICE_LOW_BATTERY` | Device battery below threshold |
| `IOT_SIGNAL_QUALITY_DEGRADED` | Persistent poor signal quality |
| `IOT_AUTO_RETRIAGE` | System-triggered retriage from IoT data |

#### Alert Severities

`CRITICAL` → `HIGH` → `MEDIUM` → `LOW`

#### API

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/alerts/visit/{visitId}` | Alerts for a visit |
| `GET` | `/api/v1/alerts/hospital/{id}/unacknowledged` | Unacknowledged alert queue |
| `GET` | `/api/v1/alerts/hospital/{id}/critical` | Critical alerts only |
| `POST` | `/api/v1/alerts/{id}/acknowledge` | Acknowledge an alert |

---

### 6.9 Medication Module

**Package:** `module/medication`

Implements a **Medication Administration Record (MAR)** — the full prescribe → administer → countersign lifecycle.

`MedicationAdministration` entity fields:

| Field | Description |
|---|---|
| `visit` | Patient visit |
| `medicationName` | Drug name |
| `dose` | Numeric dose |
| `unit` | Dose unit (mg, ml, etc.) |
| `route` | `MedicationRoute` enum |
| `status` | `MedicationStatus` enum |
| `prescribedBy` | Prescribing clinician (User FK) |
| `administeredBy` | Administering nurse (User FK) |
| `countersignedBy` | Countersigning clinician (User FK) |
| `prescribedAt` / `administeredAt` / `countersignedAt` | Timestamps |
| `indication` | Clinical indication |
| `notes` | Administration notes |

**MedicationRoute:** `ORAL`, `IV`, `IM`, `SC`, `SUBLINGUAL`, `TOPICAL`, `INHALED`, `RECTAL`, `NASAL`, `OPHTHALMIC`, `OTIC`, `OTHER`

**MedicationStatus:** `PRESCRIBED`, `ADMINISTERED`, `COUNTERSIGNED`, `HELD`, `CANCELLED`, `REFUSED`

#### API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/medications/prescribe` | Prescribe a medication |
| `POST` | `/api/v1/medications/{id}/administer` | Record administration |
| `POST` | `/api/v1/medications/{id}/countersign` | Countersign |
| `GET` | `/api/v1/medications/visit/{visitId}` | MAR for a visit |
| `DELETE` | `/api/v1/medications/{id}` | Cancel (soft delete) |

---

### 6.10 Clinical Documentation Module

**Package:** `module/clinical`

Three sub-domains: Diagnoses, Investigations, Clinical Notes.

#### Diagnosis

`Diagnosis` entity: `visit`, `diagnosisCode`, `diagnosisName`, `diagnosisType` (`DiagnosisType`: `PRESENTING`, `WORKING`, `CONFIRMED`, `DIFFERENTIAL`, `DISCHARGE`), `confirmedBy`, `confirmedAt`, `notes`.

| Method | Endpoint |
|---|---|
| `POST` | `/api/v1/diagnoses` |
| `GET` | `/api/v1/diagnoses/visit/{visitId}` |
| `DELETE` | `/api/v1/diagnoses/{id}` |

#### Investigation

`Investigation` entity: `visit`, `investigationType` (`InvestigationType`: `BLOOD_TEST`, `URINE_TEST`, `IMAGING`, `ECG`, `MICROBIOLOGY`, `HISTOLOGY`, `OTHER`), `testName`, `orderedBy`, `orderedAt`, `status` (`InvestigationStatus`: `ORDERED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`), `result`, `normalRange`, `isAbnormal`, `resultNotes`, `resultAt`.

| Method | Endpoint |
|---|---|
| `POST` | `/api/v1/investigations/order` |
| `POST` | `/api/v1/investigations/{id}/result` |
| `GET` | `/api/v1/investigations/visit/{visitId}` |

#### Clinical Note

`ClinicalNote` entity: `visit`, `author` (User FK), `noteType` (`NoteType`: `NURSING`, `DOCTOR`, `TRIAGE`, `HANDOVER`, `PROCEDURE`, `REFERRAL`, `DISCHARGE_SUMMARY`), `content` (TEXT), `noteTime`.

| Method | Endpoint |
|---|---|
| `POST` | `/api/v1/notes` |
| `GET` | `/api/v1/notes/visit/{visitId}` |
| `DELETE` | `/api/v1/notes/{id}` |

---

### 6.11 IoT Integration Module

**Package:** `module/iot`

The most architecturally significant module in the system. Enables ESP32 devices to stream patient vitals in real time and drives AI-powered deterioration detection.

#### Architecture Overview

```
ESP32 Device
    │
    │ POST /api/v1/iot/stream/ingest
    │ Header: X-Device-API-Key: st_dev_<48-byte-base64url>
    │ Body: DeviceVitalPayload (JSON)
    ▼
IoTStreamController
    │
    ├─ authenticateDevice(apiKey)          → IoTDevice
    ├─ processHeartbeat(device, ip)
    ├─ findActiveSessionForDevice(id)      → DeviceSession
    │
    ├─ VitalStreamService.ingestVitals()
    │       ├─ VitalValidationEngine.validate()
    │       ├─ Persist VitalStream (all readings, valid + invalid)
    │       ├─ Update session statistics
    │       └─ Return DeviceAckResponse
    │
    └─ ContinuousMonitoringEngine.analyseAndRespond()
            ├─ Query last 5-min validated readings
            ├─ SpO2 override check (< 92% → CRITICAL)
            ├─ Single vital critical check
            ├─ Multi-vital abnormality check (≥ 2 abnormal)
            ├─ Rapid decline detection (window comparison)
            ├─ TEWS escalation detection (stream TEWS vs current)
            ├─ Generate ClinicalAlert (DETERIORATION_DETECTED)
            └─ Auto-retriage (if shouldTriggerRetriage() + escalation)
```

#### Device Lifecycle

```
REGISTERED → ONLINE → MONITORING → ONLINE → OFFLINE → DECOMMISSIONED
                          │
                    (session active)
```

State transitions:
- `REGISTERED → ONLINE`: first heartbeat received
- `ONLINE → MONITORING`: `startMonitoring()` called by nurse
- `MONITORING → ONLINE`: `stopMonitoring()` called or session ended
- `ONLINE/MONITORING → OFFLINE`: heartbeat scheduler detects timeout

#### Entities

**`IoTDevice`** — Device registry  
One record per physical device. Contains hardware identity (`serialNumber`, `macAddress`), auth credential (`apiKey`), current status, telemetry metadata (battery, RSSI, IP), and configuration (heartbeat timeout, data interval).

**`DeviceSession`** — Monitoring session  
Links one device to one visit for a time window. Only ONE active session allowed per device at any time. Accumulates statistics: `totalReadings`, `rejectedReadings`, `alertsGenerated`, `retriagesTriggered`.

**`VitalStream`** — High-frequency time-series  
Every reading from the device is persisted here — including rejected readings (for audit). At 5-second intervals, generates ~17,280 rows/patient/day. Contains raw vitals, ECG data, signal quality metadata, and validation outcome.

**Design Rationale — Two Vital Tables:**

| Table | Frequency | Purpose |
|---|---|---|
| `vital_streams` | Every 1–5 seconds | Trend analysis, deterioration detection, real-time display |
| `vital_signs` | Every few minutes | Clinical snapshots, TEWS calculation, medical record |

`VitalStreamService.createVitalSnapshot()` aggregates recent stream data into a `VitalSigns` record when TEWS calculation is needed.

#### VitalValidationEngine

Medical-grade noise filtering applied to every reading before persistence:

| Check | Rule |
|---|---|
| Heart Rate | 15 – 300 bpm (outside range → rejected) |
| SpO2 | 30 – 100% |
| Respiratory Rate | 2 – 80 breaths/min |
| Temperature | 25.0 – 45.0 °C |
| Systolic BP | 30 – 300 mmHg |
| Diastolic BP | 15 – 200 mmHg; must be < SBP |
| Blood Glucose | 0.5 – 50.0 mmol/L |
| Completeness | At least one vital must be present |
| Perfusion Index | < 0.2 → SpO2 reading flagged as unreliable (warning, not rejection) |

Signal quality (`GOOD` / `ACCEPTABLE` / `POOR` / `INVALID`) is assessed from battery level, WiFi RSSI, and perfusion index.

#### ContinuousMonitoringEngine

The AI deterioration detection engine. Runs on every validated reading. Analysis window: **5 minutes**.

**Detection checks (in priority order):**

1. **`SPO2_OVERRIDE`** — SpO2 < 92% → immediately `CRITICAL` (Rwanda protocol override)
2. **`SINGLE_VITAL_CRITICAL`** — any vital in the extreme danger zone:
   - HR > 130 or < 40 bpm
   - RR > 30 breaths/min
   - SBP < 70 or > 200 mmHg
   - Temp > 40.0°C or < 34.0°C
3. **`MULTI_VITAL_TREND`** — ≥ 2 vitals simultaneously abnormal (HR > 110 or < 50, RR > 20 or < 9, SpO2 < 95%, SBP > 199 or < 80, Temp > 38.4 or < 35)
4. **`RAPID_DECLINE`** — within the 5-minute window:
   - HR drop > 30 bpm or rise > 40 bpm
   - SpO2 drop > 5%
   - RR rise > 10 breaths/min
   - SBP drop > 30 mmHg
5. **TEWS Escalation** — stream-computed TEWS exceeds stored TEWS by > 2 points

**Alert Severity Mapping:**

| Pattern | Severity |
|---|---|
| `SPO2_OVERRIDE`, `SINGLE_VITAL_CRITICAL`, `RESPIRATORY_FAILURE_PATTERN` | `CRITICAL` |
| `RAPID_DECLINE`, `HEMODYNAMIC_INSTABILITY`, `SEPSIS_PATTERN` | `HIGH` |
| `MULTI_VITAL_TREND`, `SUSTAINED_ABNORMALITY` | `MEDIUM` |

**Auto-Retriage Logic:**

Auto-retriage fires when:
1. Deterioration is detected
2. Retriage cooldown has passed (10-minute minimum between auto-retriages)
3. The computed triage category is strictly *higher severity* than the current one (never downgrades automatically)

Auto-retriage creates a `TriageRecord` with `isSystemTriggered = true` and `previousCategory` populated for audit.

#### DeviceService

Manages device lifecycle:

| Method | Description |
|---|---|
| `registerDevice()` | Creates device, generates cryptographically-secure API key (`st_dev_` + 48-byte base64url) |
| `authenticateDevice(apiKey)` | Used by stream endpoints — looks up device by API key |
| `processHeartbeat()` | Updates `lastHeartbeatAt`, transitions REGISTERED/OFFLINE → ONLINE |
| `startMonitoring()` | Creates `DeviceSession`, transitions device → MONITORING |
| `stopMonitoring()` | Ends session, transitions device → ONLINE |
| `findStaleDevices()` | Returns devices that missed heartbeat (cutoff: 60s global, per-device timeout applied after) |

#### DeviceHeartbeatScheduler

`@Scheduled` task running every **15 seconds** (configurable via `smarttriage.iot.heartbeat-check-interval-ms`).

For each stale device:
1. Mark device `OFFLINE`
2. If device was `MONITORING` → generate **CRITICAL** alert: `"DEVICE DISCONNECTED — Patient Unmonitored"`
3. Auto-close the monitoring session with reason `"Device disconnected (heartbeat timeout)"`

This is the fail-safe: even on total network loss, the system detects the absence of data and alerts clinical staff.

#### API Endpoints

**Device Management** (JWT authenticated):

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/iot/devices` | ADMIN/DOCTOR/NURSE | Register new device |
| `GET` | `/api/v1/iot/devices/{id}` | Any authenticated | Device details |
| `GET` | `/api/v1/iot/devices/hospital/{hospitalId}` | Any authenticated | All devices for hospital |
| `GET` | `/api/v1/iot/devices/available/{hospitalId}` | Any authenticated | Available (ONLINE, unlinked) devices |
| `POST` | `/api/v1/iot/monitoring/start` | ADMIN/DOCTOR/NURSE | Start monitoring session |
| `POST` | `/api/v1/iot/monitoring/stop/{sessionId}` | ADMIN/DOCTOR/NURSE | Stop monitoring session |
| `GET` | `/api/v1/iot/monitoring/active/{hospitalId}` | Any authenticated | All active sessions |
| `GET` | `/api/v1/iot/monitoring/session/{sessionId}` | Any authenticated | Session details |
| `GET` | `/api/v1/iot/monitoring/history/{visitId}` | Any authenticated | Session history |

**Stream Data** (JWT authenticated):

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/iot/stream/latest/{visitId}` | Latest validated reading |
| `GET` | `/api/v1/iot/stream/recent/{visitId}?count=60` | Recent N readings |
| `GET` | `/api/v1/iot/stream/history/{visitId}` | Paginated stream history |
| `GET` | `/api/v1/iot/stream/session/{sessionId}` | All readings for a session |

**Device-Facing** (API key auth — NOT JWT):

| Method | Endpoint | Auth Header | Description |
|---|---|---|---|
| `POST` | `/api/v1/iot/stream/ingest` | `X-Device-API-Key` | Ingest vital payload |
| `POST` | `/api/v1/iot/stream/heartbeat` | `X-Device-API-Key` | Device keepalive |

#### ESP32 Wire Format — `DeviceVitalPayload`

```json
{
  "serialNumber": "ESP32-001-KFH",
  "capturedAt": "2026-02-26T10:30:00Z",
  "sequenceNumber": 1042,
  "heartRate": 88,
  "spo2": 97,
  "respiratoryRate": 18,
  "temperature": 37.2,
  "systolicBp": 122,
  "diastolicBp": 78,
  "bloodGlucose": 5.4,
  "ecgWaveform": "512,514,520,580,720,600,510,508,...",
  "ecgRhythm": "NSR",
  "ecgQrsDuration": 94,
  "batteryLevel": 87,
  "wifiRssi": -62,
  "spo2PerfusionIndex": 1.8,
  "firmwareVersion": "v2.1.3"
}
```

#### Device Acknowledgment — `DeviceAckResponse`

```json
{
  "accepted": true,
  "readingId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "rejectionReason": null,
  "serverTimestamp": 1740564600000,
  "requestedIntervalSeconds": 5,
  "command": null
}
```

Commands: `INCREASE_RATE`, `RESET`, `SHUTDOWN` (for remote device management).

---

## 7. Security Architecture

### JWT Authentication

- **Stateless** — no server-side sessions
- All requests (except public endpoints) require `Authorization: Bearer <token>`
- `JwtAuthenticationFilter` intercepts every request, validates token, populates `SecurityContextHolder`
- `JwtAuthenticationEntryPoint` returns `401` with JSON body on unauthenticated access

### Public Endpoints

```
POST /api/v1/auth/login
POST /api/v1/auth/refresh
GET  /actuator/health
OPTIONS /**               (CORS preflight)
POST /api/v1/iot/stream/ingest    (device API key auth)
POST /api/v1/iot/stream/heartbeat (device API key auth)
GET  /ws/**               (WebSocket)
```

### Device Authentication

IoT devices do NOT use JWT. They authenticate with a pre-shared **API key** generated at registration:
- Format: `st_dev_` + 48 cryptographically-random bytes, base64url-encoded (no padding)
- Sent in `X-Device-API-Key` request header
- Looked up via `IoTDeviceRepository.findByApiKeyAndIsActiveTrue()`
- Serial number in payload is cross-validated against authenticated device

### Role-Based Access (`@PreAuthorize`)

Write operations (register device, start/stop monitoring, prescribe medication, perform triage) require `ADMIN`, `DOCTOR`, or `NURSE` roles. Read operations are accessible to all authenticated users.

### CSRF

Disabled — API-only backend, JWT-based authentication does not require CSRF protection.

---

## 8. Real-Time Communication — WebSocket

**Protocol:** STOMP over WebSocket  
**Endpoint:** `ws://host:8080/ws/smarttriage`

### Topics

| Topic | Payload | Description |
|---|---|---|
| `/topic/vitals/{visitId}` | `VitalStreamResponse` | Real-time vital reading for a patient |
| `/topic/alerts/{hospitalId}` | Alert data map | Alert broadcast for a hospital |
| `/topic/devices/{hospitalId}` | Device status map | Device online/offline changes |
| `/topic/triage/{visitId}` | Triage change map | Triage category change for a patient |

### `RealTimeEventPublisher`

Spring `@Service` that wraps `SimpMessagingTemplate`. Used to push events to subscribed frontend clients:

```java
publisher.publishVitalReading(vitalStream);        // → /topic/vitals/{visitId}
publisher.publishAlert(hospitalId, alertData);     // → /topic/alerts/{hospitalId}
publisher.publishDeviceStatusChange(hId, data);    // → /topic/devices/{hospitalId}
publisher.publishTriageChange(visitId, data);      // → /topic/triage/{visitId}
```

### Broker

In-memory simple broker (development). For production with horizontal scaling, replace with:
```yaml
spring.messaging.broker.relay.host=rabbitmq-host
```

---

## 9. API Reference

### Base URL
```
http://localhost:8080/api/v1
```

### Authentication Header
```
Authorization: Bearer <access_token>
```

### Complete Endpoint Summary

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/auth/login` | Public | Obtain JWT tokens |
| `POST` | `/auth/refresh` | Public | Refresh access token |
| `POST` | `/hospitals` | ADMIN | Create hospital |
| `GET` | `/hospitals/{id}` | Auth | Get hospital |
| `POST` | `/users` | ADMIN | Create user |
| `GET` | `/users/{id}` | Auth | Get user |
| `POST` | `/patients` | Auth | Register patient |
| `GET` | `/patients/{id}` | Auth | Get patient |
| `GET` | `/patients/search` | Auth | Search patients |
| `POST` | `/visits` | Auth | Create visit |
| `GET` | `/visits/{id}` | Auth | Get visit |
| `GET` | `/visits/patient/{id}` | Auth | Visit history |
| `POST` | `/vitals` | Auth | Record vitals |
| `GET` | `/vitals/visit/{id}` | Auth | Vital history |
| `GET` | `/vitals/visit/{id}/latest` | Auth | Latest vitals |
| `POST` | `/triage` | Auth | Perform triage |
| `GET` | `/triage/visit/{id}` | Auth | Triage history |
| `GET` | `/triage/visit/{id}/latest` | Auth | Latest triage |
| `GET` | `/alerts/visit/{id}` | Auth | Alerts for visit |
| `GET` | `/alerts/hospital/{id}/unacknowledged` | Auth | Unacknowledged queue |
| `POST` | `/alerts/{id}/acknowledge` | Auth | Acknowledge alert |
| `POST` | `/medications/prescribe` | DOCTOR | Prescribe medication |
| `POST` | `/medications/{id}/administer` | NURSE | Record administration |
| `POST` | `/medications/{id}/countersign` | DOCTOR | Countersign |
| `GET` | `/medications/visit/{id}` | Auth | MAR for visit |
| `POST` | `/diagnoses` | DOCTOR | Create diagnosis |
| `GET` | `/diagnoses/visit/{id}` | Auth | Diagnoses for visit |
| `POST` | `/investigations/order` | DOCTOR | Order investigation |
| `POST` | `/investigations/{id}/result` | Auth | Record result |
| `GET` | `/investigations/visit/{id}` | Auth | Investigations for visit |
| `POST` | `/notes` | Auth | Create clinical note |
| `GET` | `/notes/visit/{id}` | Auth | Notes for visit |
| `POST` | `/iot/devices` | ADMIN/NURSE | Register IoT device |
| `GET` | `/iot/devices/{id}` | Auth | Device details |
| `GET` | `/iot/devices/hospital/{id}` | Auth | Hospital devices |
| `GET` | `/iot/devices/available/{id}` | Auth | Available devices |
| `POST` | `/iot/monitoring/start` | NURSE | Start monitoring |
| `POST` | `/iot/monitoring/stop/{id}` | NURSE | Stop monitoring |
| `GET` | `/iot/monitoring/active/{id}` | Auth | Active sessions |
| `GET` | `/iot/stream/latest/{visitId}` | Auth | Latest stream reading |
| `GET` | `/iot/stream/recent/{visitId}` | Auth | Recent N readings |
| `GET` | `/iot/stream/history/{visitId}` | Auth | Stream history |
| **`POST`** | **`/iot/stream/ingest`** | **API Key** | **ESP32 data ingestion** |
| **`POST`** | **`/iot/stream/heartbeat`** | **API Key** | **Device keepalive** |

---

## 10. Enums Reference

| Enum | Values |
|---|---|
| `TriageCategory` | RED, ORANGE, YELLOW, GREEN, BLUE |
| `VisitStatus` | REGISTERED, TRIAGED, IN_PROGRESS, WAITING_FOR_RESULTS, DISCHARGED, TRANSFERRED, DECEASED |
| `AvpuScore` | ALERT, VOICE, PAIN, UNRESPONSIVE, CONFUSED |
| `MobilityStatus` | WALKING, WHEELCHAIR_HELP, STRETCHER_IMMOBILE |
| `TraumaStatus` | NO_TRAUMA, TRAUMA |
| `VitalSource` | MANUAL_ENTRY, IOT_DEVICE, AMBULANCE_TELEMETRY, NURSE_STATION |
| `DeviceStatus` | REGISTERED, ONLINE, OFFLINE, MONITORING, ERROR, DECOMMISSIONED |
| `DeviceType` | ESP32_MONITOR, PULSE_OXIMETER, ECG_MONITOR, BP_MONITOR, TEMPERATURE_PROBE, GLUCOMETER, AMBULANCE_MONITOR, OTHER |
| `SignalQuality` | GOOD, ACCEPTABLE, POOR, INVALID, UNKNOWN |
| `DeteriorationPattern` | SINGLE_VITAL_CRITICAL, MULTI_VITAL_TREND, RAPID_DECLINE, SUSTAINED_ABNORMALITY, SPO2_OVERRIDE, SEPSIS_PATTERN, RESPIRATORY_FAILURE_PATTERN, HEMODYNAMIC_INSTABILITY, DEVICE_DISCONNECTED, NONE |
| `AlertType` | TEWS_CRITICAL, TEWS_ESCALATION, VITAL_SIGN_ABNORMAL, RETRIAGE_REQUIRED, WAITING_TIME_EXCEEDED, DETERIORATION_DETECTED, SEPSIS_SCREENING, PEDIATRIC_SAFETY, REASSESSMENT_DUE, CRITICAL_LAB_RESULT, IOT_DEVICE_DISCONNECTED, IOT_DEVICE_LOW_BATTERY, IOT_SIGNAL_QUALITY_DEGRADED, IOT_AUTO_RETRIAGE |
| `AlertSeverity` | CRITICAL, HIGH, MEDIUM, LOW |
| `MedicationRoute` | ORAL, IV, IM, SC, SUBLINGUAL, TOPICAL, INHALED, RECTAL, NASAL, OPHTHALMIC, OTIC, OTHER |
| `MedicationStatus` | PRESCRIBED, ADMINISTERED, COUNTERSIGNED, HELD, CANCELLED, REFUSED |
| `DiagnosisType` | PRESENTING, WORKING, CONFIRMED, DIFFERENTIAL, DISCHARGE |
| `InvestigationType` | BLOOD_TEST, URINE_TEST, IMAGING, ECG, MICROBIOLOGY, HISTOLOGY, OTHER |
| `InvestigationStatus` | ORDERED, IN_PROGRESS, COMPLETED, CANCELLED |
| `NoteType` | NURSING, DOCTOR, TRIAGE, HANDOVER, PROCEDURE, REFERRAL, DISCHARGE_SUMMARY |
| `Role` | ADMIN, DOCTOR, NURSE, RECEPTIONIST, LAB_TECHNICIAN |
| `Gender` | MALE, FEMALE, OTHER |
| `ArrivalMode` | WALK_IN, AMBULANCE, REFERRED, SELF_REFERRAL |
| `DispositionType` | ADMITTED, DISCHARGED_HOME, TRANSFERRED, DECEASED, LEFT_WITHOUT_BEING_SEEN, LEFT_AGAINST_MEDICAL_ADVICE |

---

## 11. Configuration Reference

### `application.properties`

```properties
# Server
server.port=8080

# Jackson 3.x
spring.jackson.default-property-inclusion=non_null

# JPA
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.time_zone=UTC

# Flyway
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# Actuator
management.endpoints.web.exposure.include=health,info,metrics

# JWT
smarttriage.security.jwt.secret=${JWT_SECRET:<base64-encoded-secret>}
smarttriage.security.jwt.access-token-expiration-ms=900000    # 15 min
smarttriage.security.jwt.refresh-token-expiration-ms=86400000 # 24 hr

# IoT
smarttriage.iot.heartbeat-check-interval-ms=15000   # Scheduler interval
smarttriage.iot.default-data-interval-seconds=5      # Device default
smarttriage.iot.default-heartbeat-timeout-seconds=30 # Device default
```

### Environment Variable Overrides (Production)

```bash
JWT_SECRET=<256-bit base64-encoded secret>
DATABASE_URL=jdbc:postgresql://<host>:5432/<db>
DATABASE_USERNAME=<user>
DATABASE_PASSWORD=<password>
```

---

## 12. File Inventory

### Total: 143 Java source files

#### `config/` (4 files)
| File | Description |
|---|---|
| `JpaAuditingConfig` | Enables Spring Data JPA auditing, provides `AuditorAware` |
| `PasswordEncoderConfig` | BCrypt(12) bean — extracted to break circular dependency |
| `SecurityConfig` | JWT filter chain, public endpoints, role-based access |
| `WebSocketConfig` | STOMP broker configuration, `/ws/smarttriage` endpoint |

#### `security/` (3 files)
| File | Description |
|---|---|
| `JwtService` | Token generation, validation, claim extraction |
| `JwtAuthenticationFilter` | Per-request JWT validation, populates SecurityContext |
| `JwtAuthenticationEntryPoint` | Returns structured JSON 401 on auth failure |

#### `common/enums/` (25 enums)
AlertSeverity, AlertType, ArrivalMode, AvpuScore, DeteriorationPattern, DeviceStatus, DeviceType, DiagnosisType, DispositionType, Gender, InvestigationStatus, InvestigationType, MedicationRoute, MedicationStatus, MobilityStatus, NoteType, Role, SignalQuality, TraumaStatus, TriageCategory, VisitStatus, VitalSource, + 3 others

#### `module/iot/` (17 files — all new this session)
| Category | Files |
|---|---|
| Entities | `IoTDevice`, `DeviceSession`, `VitalStream` |
| Repositories | `IoTDeviceRepository`, `DeviceSessionRepository`, `VitalStreamRepository` |
| DTOs | `RegisterDeviceRequest`, `DeviceResponse`, `StartMonitoringRequest`, `DeviceSessionResponse`, `DeviceVitalPayload`, `VitalStreamResponse`, `DeviceAckResponse` |
| Mapper | `IoTMapper` |
| Engines | `VitalValidationEngine`, `ContinuousMonitoringEngine` |
| Services | `DeviceService`, `VitalStreamService`, `RealTimeEventPublisher` |
| Controllers | `IoTDeviceController`, `IoTStreamController` |
| Scheduler | `DeviceHeartbeatScheduler` |

#### `module/medication/` (7 files)
Entity, Repository, 3 DTOs, Mapper, Service, Controller

#### `module/clinical/` (14 files)
3 Entities (Diagnosis, Investigation, ClinicalNote), 3 Repositories, 6 DTOs, Mapper, 3 Services, 3 Controllers

#### `module/triage/` (10 files)
4 Engines (TewsCalculator, PediatricTewsCalculator, RwandaTriageDecisionEngine, RwandaPediatricTriageDecisionEngine), Entity, Repository, 2 DTOs, Mapper, Service, Controller

#### Other modules (alert, auth, hospital, patient, user, visit, vital)
Approximately 7 files each following the standard entity/repo/service/controller/dto/mapper pattern

---

*SmartTriage-server — Emergency Department & ICU Clinical Workflow Backend*  
*Rwanda National Standard Triage Protocol Implementation*  
*Built with Spring Boot 4.0.3 · Java 21 · PostgreSQL 14 · 143 source files*
