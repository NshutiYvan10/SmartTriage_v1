# SmartTriage - Comprehensive Project Documentation

**Version:** 1.0
**Last Updated:** April 2026
**Platform:** AI-Assisted Emergency Department & ICU Clinical Workflow System
**Target Country:** Rwanda (National Standard Triage Protocol - SATS)

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [User Roles & Permissions](#2-user-roles--permissions)
3. [Technology Stack](#3-technology-stack)
4. [Database Documentation](#4-database-documentation)
5. [Data Dictionary](#5-data-dictionary)
6. [Backend Architecture](#6-backend-architecture)
7. [Frontend Architecture](#7-frontend-architecture)
8. [System Design Support](#8-system-design-support)
9. [Module-Specific Workflows](#9-module-specific-workflows)
10. [API Reference](#10-api-reference)
11. [Real-Time Communication](#11-real-time-communication)
12. [Security Architecture](#12-security-architecture)
13. [Deployment & Configuration](#13-deployment--configuration)

---

## 1. Project Overview

### 1.1 Purpose

SmartTriage is a production-grade, AI-assisted clinical workflow platform designed for Rwandan hospitals. It implements the **Rwanda National Standard Triage Protocol** based on the **South African Triage Scale (SATS)** with the **Triage Early Warning Score (TEWS)**. The system supports multi-hospital deployment, continuous IoT-based patient monitoring, clinical decision support, and full regulatory compliance with Rwanda's Ministry of Health requirements.

### 1.2 Core Goals

- **Standardize emergency triage** across all Rwandan hospitals using SATS/TEWS
- **Reduce patient deterioration** through real-time IoT vital monitoring and AI-powered alerts
- **Enable national oversight** with multi-hospital governance, MOH reporting, and quality metrics
- **Support clinical workflows** end-to-end: registration, triage, treatment, disposition, referral, handover
- **Ensure patient safety** with medication safety checks, sepsis screening, infection isolation, and ICU escalation protocols

### 1.3 Key Features

| Feature | Description |
|---------|-------------|
| **SATS/TEWS Triage** | Adult + Pediatric triage forms implementing the full South African Triage Scale |
| **IoT Vital Monitoring** | ESP32 devices stream vitals every 5 seconds via API key authentication |
| **AI Alert Engine** | 30+ alert types with zone-aware escalation and SATS target time enforcement |
| **Dynamic Re-triage** | Automatic category escalation/de-escalation based on vital trends |
| **Sepsis Screening** | qSOFA + SIRS scoring with 1-hour bundle tracking |
| **Fast-Track Protocols** | Stroke (BE-FAST) and STEMI/NSTEMI door-to-needle time tracking |
| **Medication Safety** | Drug interaction, allergy, and dose-range checking against Rwanda REML formulary |
| **Lab Integration** | Full lab order lifecycle with critical value notification |
| **Referral Management** | Inter-hospital referral workflow (SAMU integration, transport tracking) |
| **Clinical Documentation** | Structured clinical notes with co-signing and amendment trails |
| **Infection Isolation** | Screening for notifiable diseases (TB, Ebola, Cholera, etc.) with PPE requirements |
| **ICU Escalation** | Trigger-based ICU bed requests and stabilization tracking |
| **Clinical Pathways** | Evidence-based protocol activation (malaria, trauma, respiratory, etc.) |
| **Quality Metrics** | KPI tracking, MOH reporting, safety incident management |
| **National Governance** | Clinical policy management, audit trails, compliance monitoring |
| **Offline Sync** | Resilient operation with offline data capture and conflict resolution |
| **Surge Prediction** | ED capacity forecasting and surge risk assessment |

### 1.4 Multi-Hospital Architecture

```
Rwanda Ministry of Health (National Level)
    |
    |-- SUPER_ADMIN (National oversight)
    |       |-- Hospital A (District Hospital)
    |       |       |-- HOSPITAL_ADMIN
    |       |       |-- Doctors, Nurses, Triage Nurses, etc.
    |       |
    |       |-- Hospital B (Regional Referral Hospital)
    |       |       |-- HOSPITAL_ADMIN
    |       |       |-- Doctors, Nurses, Triage Nurses, etc.
    |       |
    |       |-- Hospital C (Tertiary/Teaching Hospital)
    |               |-- HOSPITAL_ADMIN
    |               |-- Doctors, Nurses, Triage Nurses, etc.
```

### 1.5 Seed Data

- **Admin Email:** admin@smarttriage.com
- **Admin Password:** SmartTriage@2026
- **Default Hospital ID:** a0000000-0000-0000-0000-000000000001

---

## 2. User Roles & Permissions

### 2.1 Role Definitions

| Role | Label | Description | Color |
|------|-------|-------------|-------|
| `SUPER_ADMIN` | Super Admin | System-wide configuration & multi-tenant management | Violet |
| `HOSPITAL_ADMIN` | Hospital Admin | Hospital-level user & configuration management | Indigo |
| `DOCTOR` | Doctor | Reviews triage results & makes disposition decisions | Cyan |
| `NURSE` | Nurse | Performs primary triage & records patient vitals | Emerald |
| `TRIAGE_NURSE` | Triage Nurse | Dedicated triage responsibilities in the ED | Amber |
| `REGISTRAR` | Registrar | Patient registration and admission processing | Teal |
| `PARAMEDIC` | Paramedic | Pre-hospital emergency care and patient transport | Orange |
| `LAB_TECHNICIAN` | Lab Technician | Laboratory investigations and results management | Purple |
| `READ_ONLY` | Read Only | Audit, reporting & observation-only access | Slate |

### 2.2 Designations (Sub-roles)

Each role has specific designations that further define clinical seniority:

| Designation | Typical Role |
|-------------|-------------|
| ED_HEAD | DOCTOR |
| CONSULTANT | DOCTOR |
| SENIOR_MEDICAL_OFFICER | DOCTOR |
| MEDICAL_OFFICER | DOCTOR |
| RESIDENT | DOCTOR |
| INTERN | DOCTOR |
| NURSE_MANAGER | NURSE |
| CHARGE_NURSE | NURSE |
| SENIOR_NURSE | NURSE |
| STAFF_NURSE | NURSE |
| STUDENT_NURSE | NURSE |
| HEAD_LAB_TECHNICIAN | LAB_TECHNICIAN |
| LAB_TECHNICIAN | LAB_TECHNICIAN |
| SENIOR_REGISTRAR | REGISTRAR |
| REGISTRAR | REGISTRAR |
| SENIOR_PARAMEDIC | PARAMEDIC |
| PARAMEDIC | PARAMEDIC |
| UNSPECIFIED | Any |

### 2.3 Page-Level Access Matrix

| Page | SUPER_ADMIN | HOSP_ADMIN | DOCTOR | NURSE | TRIAGE_NURSE | REGISTRAR | PARAMEDIC | LAB_TECH | READ_ONLY |
|------|:-----------:|:----------:|:------:|:-----:|:------------:|:---------:|:---------:|:--------:|:---------:|
| Dashboard | X | X | X | X | X | X | X | X | X |
| Registration (entry) | - | - | - | X | X | X | X | - | - |
| Patients | - | X | X | X | X | X | X | X | X |
| Triage Queue | - | - | X | X | X | - | - | - | - |
| Monitoring | - | X | X | X | X | - | - | - | - |
| AI Alerts | - | X | X | X | X | - | - | - | - |
| Audit Trail | X | - | - | - | - | - | - | - | X |
| Reports | X | X | X | - | - | - | - | - | X |
| Settings | X | X | - | - | - | - | - | - | - |
| Admin (Users/Hospitals) | X | X | - | - | - | - | - | - | - |
| Sepsis | - | - | X | X | - | - | - | - | - |
| Fast-Track | - | - | X | X | - | - | - | - | - |
| Hypoglycemia | - | - | X | X | - | - | - | - | - |
| Isolation | - | - | X | X | - | - | - | - | - |
| Pathways | - | - | X | X | - | - | - | - | - |
| Med Safety | - | - | X | - | - | - | - | - | - |
| ICU Escalation | - | - | X | - | - | - | - | - | - |
| Referral | - | - | X | X | - | X | X | - | - |
| Documentation | - | - | X | X | X | - | - | - | - |
| Handover | - | - | X | X | X | - | X | - | - |
| Lab | - | - | X | X | - | - | - | X | - |
| Safety Incidents | X | X | - | X | - | - | - | - | - |
| MOH Reports | X | X | - | - | - | - | - | - | X |
| Governance | X | X | - | - | - | - | - | - | - |
| Quality | X | - | - | - | - | - | - | - | X |
| Prediction | X | X | - | - | - | - | - | - | - |

### 2.4 Feature-Level Permissions

| Feature | SUPER_ADMIN | HOSP_ADMIN | DOCTOR | NURSE | TRIAGE_NURSE | REGISTRAR | PARAMEDIC | LAB_TECH | READ_ONLY |
|---------|:-----------:|:----------:|:------:|:-----:|:------------:|:---------:|:---------:|:--------:|:---------:|
| register_patient | - | - | - | X | X | X | X | - | - |
| start_triage | - | - | - | X | X | - | - | - | - |
| record_vitals | - | - | - | X | X | - | X | - | - |
| override_category | - | - | X | - | - | - | - | - | - |
| acknowledge_alert | - | X | X | X | X | - | - | - | - |
| add_clinical_note | - | - | X | X | X | - | - | - | - |
| export_report | X | X | X | - | - | - | - | - | - |
| manage_users | X | X | - | - | - | - | - | - | - |
| manage_settings | X | X | - | - | - | - | - | - | - |
| view_audit | X | - | - | - | - | - | - | - | X |
| view_reports | X | X | X | - | - | - | - | X | X |
| view_monitoring | - | X | X | X | X | - | X | - | - |

---

## 3. Technology Stack

### 3.1 Backend

| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | Spring Boot | 4.0.3 |
| Language | Java | 21 |
| Database | PostgreSQL | 14+ |
| Migrations | Flyway | Auto |
| ORM | Hibernate/JPA | Auto |
| Authentication | JWT (access + refresh tokens) | Custom |
| Real-time | WebSocket (STOMP over SockJS) | Spring WebSocket |
| API Style | RESTful JSON | /api/v1 |
| Build Tool | Gradle | Wrapper |

### 3.2 Frontend

| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | React | 18.2 |
| Language | TypeScript | 5.2 |
| Build Tool | Vite | 5.0.8 |
| State Management | Zustand | 4.4.7 |
| Styling | TailwindCSS | 3.3.6 |
| Routing | React Router | 6.20.0 |
| Charts | Recharts | 2.10.3 |
| Icons | Lucide React | 0.294.0 |
| WebSocket | @stomp/stompjs + SockJS | 7.3.0 / 1.6.1 |
| Animation | GSAP | 3.14.2 |
| Date Utilities | date-fns | 3.0.6 |

### 3.3 IoT / Hardware

| Component | Technology |
|-----------|-----------|
| Device Platform | ESP32 microcontroller |
| Communication | HTTP POST (API key auth) + heartbeat |
| Data Interval | 5 seconds (configurable) |
| Heartbeat Timeout | 30 seconds |
| Supported Sensors | Pulse oximeter, ECG, BP, temperature, glucometer, respiratory rate |

---

## 4. Database Documentation

### 4.1 Overview

- **Total Tables:** 33+
- **Migration Files:** V1 through V14 (Flyway)
- **Primary Keys:** UUID (gen_random_uuid())
- **Audit Columns:** All tables include created_at, updated_at, created_by, last_modified_by, is_active, version
- **Soft Delete:** All entities use is_active flag (never hard-deleted)
- **Optimistic Locking:** @Version field on all entities

### 4.2 Entity Relationship Summary

```
hospitals (1) ─────< users (N)
hospitals (1) ─────< patients (N)
hospitals (1) ─────< iot_devices (N)
hospitals (1) ─────< shift_assignments (N)
hospitals (1) ─────< safety_incidents (N)
hospitals (1) ─────< handover_reports (N)
hospitals (1) ─────< offline_sync_records (N)
hospitals (1) ─────< system_health_statuses (N)

patients (1) ──────< visits (N)

visits (1) ────────< vital_signs (N)
visits (1) ────────< triage_records (N)
visits (1) ────────< clinical_alerts (N)
visits (1) ────────< medication_administrations (N)
visits (1) ────────< diagnoses (N)
visits (1) ────────< investigations (N)
visits (1) ────────< clinical_notes (N)
visits (1) ────────< device_sessions (N)
visits (1) ────────< vital_streams (N)
visits (1) ────────< sepsis_screenings (N)
visits (1) ────────< fast_track_activations (N)
visits (1) ────────< hypoglycemia_events (N)
visits (1) ────────< infection_screenings (N)
visits (1) ────────< clinical_documents (N)
visits (1) ────────< medication_safety_checks (N)
visits (1) ────────< lab_orders (N)
visits (1) ────────< pathway_activations (N)
visits (1) ────────< icu_escalations (N)
visits (1) ────────< referrals (N)
visits (1) ────────< handover_reports (N)

iot_devices (1) ───< device_sessions (N)

clinical_pathways (1) ──< pathway_steps (N)
clinical_pathways (1) ──< pathway_activations (N)
pathway_activations (1) < pathway_step_completions (N)
pathway_steps (1) ─────< pathway_step_completions (N)

clinical_documents (1) ─< clinical_documents (N) [amendments]

triage_records ────> users (triaged_by)
triage_records ────> vital_signs
clinical_alerts ───> users (acknowledged_by, target_doctor)
shift_assignments ─> users
```

### 4.3 Table Definitions

#### 4.3.1 hospitals
Core multi-tenant entity. Each hospital operates independently.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | UUID | PK | Primary key |
| name | VARCHAR(255) | NOT NULL | Hospital name |
| hospital_code | VARCHAR(20) | NOT NULL, UNIQUE | Unique hospital identifier |
| address | VARCHAR(500) | | Physical address |
| city | VARCHAR(100) | | City |
| province | VARCHAR(100) | | Province |
| country | VARCHAR(3) | | Country code (RWA) |
| phone_number | VARCHAR(20) | | Contact phone |
| email | VARCHAR(255) | | Contact email |
| tier | VARCHAR(20) | | District, Regional, Tertiary |
| bed_capacity | INTEGER | | Total bed count |
| ed_capacity | INTEGER | | Emergency department beds |
| icu_capacity | INTEGER | | ICU beds |
| is_active | BOOLEAN | DEFAULT TRUE | Soft delete flag |
| version | BIGINT | DEFAULT 0 | Optimistic locking |
| created_at | TIMESTAMPTZ | NOT NULL | Creation timestamp |
| updated_at | TIMESTAMPTZ | | Last update |
| created_by | VARCHAR(255) | | Creator user |
| last_modified_by | VARCHAR(255) | | Last modifier |

**Indexes:** hospital_code, is_active

#### 4.3.2 users
Staff accounts with role-based access. Implements Spring Security UserDetails.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | UUID | PK | Primary key |
| first_name | VARCHAR(100) | NOT NULL | First name |
| last_name | VARCHAR(100) | NOT NULL | Last name |
| email | VARCHAR(255) | NOT NULL, UNIQUE | Login email |
| password_hash | VARCHAR(255) | NOT NULL | BCrypt hash |
| phone_number | VARCHAR(20) | | Contact phone |
| role | VARCHAR(30) | NOT NULL | Role enum value |
| designation | VARCHAR(50) | | Sub-role designation |
| employee_number | VARCHAR(50) | | Staff ID |
| professional_license | VARCHAR(50) | | License number |
| department | VARCHAR(100) | | Department name |
| hospital_id | UUID | FK -> hospitals, NOT NULL | Assigned hospital |
| account_locked | BOOLEAN | DEFAULT FALSE | Lockout flag |
| failed_login_attempts | INTEGER | DEFAULT 0 | Failed login counter |
| is_active | BOOLEAN | DEFAULT TRUE | Soft delete |
| version | BIGINT | DEFAULT 0 | Optimistic locking |

**Indexes:** email (unique), hospital_id, role, is_active, employee_number

#### 4.3.3 patients
Patient demographics. Hospital-scoped.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | UUID | PK | Primary key |
| first_name | VARCHAR(100) | NOT NULL | First name |
| last_name | VARCHAR(100) | NOT NULL | Last name |
| date_of_birth | DATE | | DOB (pediatric if age < 13) |
| gender | VARCHAR(10) | | MALE, FEMALE, OTHER, UNKNOWN |
| national_id | VARCHAR(30) | | Rwanda national ID |
| medical_record_number | VARCHAR(30) | | MRN |
| phone_number | VARCHAR(20) | | Patient phone |
| address | VARCHAR(500) | | Address |
| emergency_contact_name | VARCHAR(200) | | Emergency contact |
| emergency_contact_phone | VARCHAR(20) | | Contact phone |
| blood_type | VARCHAR(5) | | Blood type |
| known_allergies | TEXT | | Allergy list |
| chronic_conditions | TEXT | | Chronic conditions |
| hospital_id | UUID | FK -> hospitals, NOT NULL | Hospital |

**Indexes:** hospital_id, national_id, medical_record_number, is_active, date_of_birth, (last_name, first_name)

#### 4.3.4 visits
Each ED encounter. Central entity linking all clinical data.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | UUID | PK | Primary key |
| patient_id | UUID | FK -> patients, NOT NULL | Patient |
| hospital_id | UUID | FK -> hospitals, NOT NULL | Hospital |
| visit_number | VARCHAR(30) | NOT NULL, UNIQUE | Visit identifier |
| arrival_mode | VARCHAR(20) | | WALK_IN, AMBULANCE, REFERRAL, etc. |
| arrival_time | TIMESTAMPTZ | NOT NULL | Arrival timestamp |
| chief_complaint | TEXT | | Presenting complaint |
| status | VARCHAR(30) | NOT NULL, DEFAULT 'REGISTERED' | Visit status |
| current_triage_category | VARCHAR(10) | | RED, ORANGE, YELLOW, GREEN, BLUE |
| current_tews_score | INTEGER | | Latest TEWS score |
| triage_time | TIMESTAMPTZ | | When triaged |
| assessment_start_time | TIMESTAMPTZ | | Doctor assessment start |
| disposition_type | VARCHAR(30) | | Disposition decision |
| disposition_time | TIMESTAMPTZ | | When disposed |
| disposition_notes | TEXT | | Disposition notes |
| referring_facility | VARCHAR(255) | | If arrived by referral |
| is_pediatric | BOOLEAN | DEFAULT FALSE | Pediatric flag |
| retriage_count | INTEGER | DEFAULT 0 | Times re-triaged |

**Visit Statuses:** REGISTERED, AWAITING_TRIAGE, TRIAGED, AWAITING_ASSESSMENT, UNDER_ASSESSMENT, UNDER_TREATMENT, UNDER_OBSERVATION, PENDING_DISPOSITION, DISCHARGED, ADMITTED, TRANSFERRED, ICU_ADMITTED, LEFT_WITHOUT_BEING_SEEN, DECEASED

**Indexes:** patient_id, hospital_id, status, current_triage_category, arrival_time, is_active

#### 4.3.5 vital_signs
Manual vital sign recordings.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| id | UUID | PK | Primary key |
| visit_id | UUID | FK -> visits, NOT NULL | Visit |
| recorded_at | TIMESTAMPTZ | NOT NULL | Recording time |
| respiratory_rate | INTEGER | | Breaths/min |
| heart_rate | INTEGER | | BPM |
| systolic_bp | INTEGER | | mmHg |
| diastolic_bp | INTEGER | | mmHg |
| temperature | DOUBLE PRECISION | | Celsius |
| spo2 | INTEGER | | Percentage |
| avpu | VARCHAR(15) | | ALERT, CONFUSED, VERBAL, PAIN, UNRESPONSIVE |
| blood_glucose | DOUBLE PRECISION | | mmol/L |
| pain_score | INTEGER | | 0-10 |
| gcs_score | INTEGER | | 3-15 |
| source | VARCHAR(20) | NOT NULL, DEFAULT 'MANUAL_ENTRY' | MANUAL_ENTRY, IOT_DEVICE |
| device_id | VARCHAR(50) | | Source device |
| notes | TEXT | | Clinical notes |

**Indexes:** visit_id, recorded_at, source, is_active

#### 4.3.6 triage_records
Complete triage assessment including SATS emergency signs, discriminators, and TEWS scoring. Supports both Adult and Child (age 3-12) forms.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| triaged_by_id | UUID | FK -> users |
| vital_signs_id | UUID | FK -> vital_signs |
| triage_time | TIMESTAMPTZ | Assessment time |
| **Emergency Signs** | | |
| has_airway_compromise | BOOLEAN | Airway blocked |
| has_breathing_distress | BOOLEAN | Severe breathing difficulty |
| has_severe_respiratory_distress | BOOLEAN | RR < 8 or absent |
| has_cardiac_arrest | BOOLEAN | No pulse |
| has_uncontrolled_haemorrhage | BOOLEAN | Uncontrolled bleeding |
| has_stab_gun_wound_neck_chest | BOOLEAN | Penetrating neck/chest |
| has_convulsions | BOOLEAN | Active seizure |
| has_coma | BOOLEAN | GCS <= 8 |
| has_hypoglycaemia | BOOLEAN | Blood glucose < 3.5 |
| has_purpuric_rash | BOOLEAN | Petechial/purpuric |
| has_burn_face_inhalation | BOOLEAN | Burns to face/airway |
| convulsion_glucose | DOUBLE PRECISION | Glucose if convulsing |
| coma_glucose | DOUBLE PRECISION | Glucose if comatose |
| **Child-Specific** | | |
| is_child_form | BOOLEAN | True = Child 3-12 form |
| child_central_cyanosis | BOOLEAN | Central cyanosis |
| child_pulse_low_or_absent | BOOLEAN | Pulse absent/low |
| child_cold_hands_composite | BOOLEAN | Cold peripheries |
| child_cold_hands_lethargic | BOOLEAN | Cold + lethargic |
| child_cold_hands_pulse_weak_fast | BOOLEAN | Cold + weak pulse |
| child_cold_hands_cap_refill | BOOLEAN | Cap refill > 3s |
| child_severe_dehydration | BOOLEAN | Severe dehydration |
| child_dehydration_skin_pinch | BOOLEAN | Slow skin pinch |
| child_dehydration_lethargy | BOOLEAN | Dehydration + lethargy |
| child_dehydration_sunken_eyes | BOOLEAN | Sunken eyes |
| child_weight_kg | DOUBLE PRECISION | Weight in kg |
| child_height_cm | DOUBLE PRECISION | Height in cm |
| **TEWS Components** | | |
| mobility | VARCHAR(15) | WALKING(0), WITH_HELP(1), STRETCHER(2) |
| avpu | VARCHAR(15) | ALERT(0), CONFUSED(1), VERBAL(1), PAIN(2), UNRESPONSIVE(3) |
| trauma_status | VARCHAR(15) | NO_TRAUMA(0), TRAUMA(1) |
| **Additional Vitals** | | |
| spo2 | INTEGER | Not TEWS-scored |
| diastolic_bp | INTEGER | Not TEWS-scored |
| blood_glucose | DOUBLE PRECISION | Not TEWS-scored |
| pain_score | INTEGER | Not TEWS-scored |
| weight_kg | DOUBLE PRECISION | Patient weight |
| height_cm | DOUBLE PRECISION | Patient height |
| **Very Urgent Medical** | | |
| vu_focal_neurologic_deficit | BOOLEAN | Focal deficit |
| vu_altered_mental_status | BOOLEAN | Altered mentation |
| vu_chest_pain | BOOLEAN | Chest pain |
| vu_poisoning_overdose | BOOLEAN | Poisoning |
| vu_pregnant_abdominal_pain | BOOLEAN | Pregnant + abdo pain |
| vu_coughing_vomiting_blood | BOOLEAN | Hemoptysis/hematemesis |
| vu_diabetic_high_glucose | BOOLEAN | DKA suspected |
| vu_aggression | BOOLEAN | Violent/aggressive |
| vu_shortness_of_breath | BOOLEAN | Dyspnea |
| **Very Urgent Trauma** | | |
| vu_burn_over_20_percent | BOOLEAN | Burns > 20% TBSA |
| vu_open_fracture | BOOLEAN | Open fracture |
| vu_threatened_limb | BOOLEAN | Threatened limb |
| vu_eye_injury | BOOLEAN | Eye injury |
| vu_large_joint_dislocation | BOOLEAN | Large joint dislocation |
| vu_severe_mechanism_of_injury | BOOLEAN | High-energy mechanism |
| vu_very_severe_pain | BOOLEAN | Pain score 8-10 |
| vu_pregnant_abdominal_trauma | BOOLEAN | Pregnant + trauma |
| **Urgent Signs** | | |
| urg_unable_to_drink_vomits | BOOLEAN | Cannot take PO |
| urg_abdominal_pain | BOOLEAN | Abdominal pain |
| urg_very_pale | BOOLEAN | Clinically pale |
| urg_pregnant_vaginal_bleeding | BOOLEAN | PV bleeding |
| urg_diabetic_very_high_glucose | BOOLEAN | Very high glucose |
| urg_finger_toe_dislocation | BOOLEAN | Small joint dislocation |
| urg_closed_fracture | BOOLEAN | Closed fracture |
| urg_burn_without_urgent_signs | BOOLEAN | Minor burn |
| urg_pregnant_trauma_non_abdominal | BOOLEAN | Pregnant + non-abdo trauma |
| urg_moderate_pain | BOOLEAN | Pain score 4-7 |
| urg_laceration_abscess | BOOLEAN | Laceration/abscess |
| urg_foreign_body_aspiration | BOOLEAN | FB aspiration |
| **Special Considerations** | | |
| special_acute_trauma | BOOLEAN | Acute trauma |
| special_seizure_history | BOOLEAN | Seizure history |
| special_assault_abuse | BOOLEAN | Assault/abuse |
| special_suicide_attempt | BOOLEAN | Suicide attempt |
| **Results** | | |
| tews_score | INTEGER | Computed TEWS (0-17) |
| triage_category | VARCHAR(10) | RED/ORANGE/YELLOW/GREEN/BLUE |
| decision_path | TEXT | Audit trail of category decision |
| is_retriage | BOOLEAN | Re-triage flag |
| is_system_triggered | BOOLEAN | AI-triggered re-triage |
| previous_category | VARCHAR(10) | Previous category if retriage |
| clinical_notes | TEXT | Free-text notes |
| presenting_complaints | TEXT | Complaints list |
| **Form Footer** | | |
| triage_nurse_name | VARCHAR(255) | Triage nurse |
| notified_doctor_name | VARCHAR(255) | Doctor notified |
| doctor_notified_at | TIMESTAMPTZ | Notification time |
| attending_doctor_name | VARCHAR(255) | Attending doctor |
| doctor_attended_at | TIMESTAMPTZ | When attended |

#### 4.3.7 clinical_alerts
AI-generated and manual alerts with zone-aware escalation.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| alert_type | VARCHAR(30) | See Alert Types enum |
| severity | VARCHAR(15) | CRITICAL, HIGH, MEDIUM, LOW, INFO |
| title | VARCHAR(255) | Alert title |
| message | TEXT | Alert details |
| is_acknowledged | BOOLEAN | Acknowledged flag |
| acknowledged_by_id | UUID | FK -> users |
| acknowledged_at | TIMESTAMPTZ | Acknowledgement time |
| auto_generated | BOOLEAN | AI-generated flag |
| target_zone | VARCHAR(20) | RESUS, ACUTE, GENERAL, etc. |
| escalation_tier | INTEGER | Escalation level (1-3) |
| escalated_at | TIMESTAMPTZ | When escalated |
| target_doctor_id | UUID | FK -> users (target doctor) |
| sats_target_minutes | INTEGER | SATS target time |

**Alert Types (30):** TEWS_CRITICAL, TEWS_ESCALATION, VITAL_SIGN_ABNORMAL, RETRIAGE_REQUIRED, WAITING_TIME_EXCEEDED, DETERIORATION_DETECTED, SEPSIS_SCREENING, PEDIATRIC_SAFETY, REASSESSMENT_DUE, CRITICAL_LAB_RESULT, IOT_DEVICE_DISCONNECTED, IOT_DEVICE_LOW_BATTERY, IOT_SIGNAL_QUALITY_DEGRADED, IOT_AUTO_RETRIAGE, DOCTOR_NOTIFICATION, DOCTOR_ESCALATION, SURGE_WARNING, INVESTIGATION_RESULTED, MEDICATION_SAFETY_BLOCK, MEDICATION_SAFETY_WARNING, STAT_LAB_OVERDUE, URGENT_LAB_OVERDUE, CRITICAL_VALUE_UNACKNOWLEDGED, REFERRAL_INITIATED, REFERRAL_STABILIZATION_INCOMPLETE, SYSTEM_OFFLINE, SYSTEM_ONLINE, SAFETY_INCIDENT_CRITICAL, ICU_ESCALATION_REQUESTED, ICU_BED_UNAVAILABLE

#### 4.3.8 medication_administrations
Prescription-to-administration chain with countersigning.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| drug_name | VARCHAR(255) | Drug name |
| dose | VARCHAR(100) | Dose with units |
| route | VARCHAR(20) | PO, IV, IM, SC, SL, PR, INH, NEB, etc. |
| frequency | VARCHAR(50) | Dosing frequency |
| prescribed_at | TIMESTAMPTZ | Prescription time |
| prescribed_by_id | UUID | FK -> users |
| prescribed_by_name | VARCHAR(255) | Prescriber name |
| administered_at | TIMESTAMPTZ | Administration time |
| administered_by_id | UUID | FK -> users |
| administered_by_name | VARCHAR(255) | Administrator name |
| countersigned_by_id | UUID | FK -> users |
| countersigned_by_name | VARCHAR(255) | Countersigner |
| countersigned_at | TIMESTAMPTZ | Countersign time |
| status | VARCHAR(20) | PRESCRIBED, ADMINISTERED, HELD, REFUSED, CANCELLED |
| notes | TEXT | Notes |

#### 4.3.9 diagnoses

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| diagnosis_type | VARCHAR(20) | PROVISIONAL, CONFIRMED, DIFFERENTIAL, WORKING |
| icd_code | VARCHAR(20) | ICD-10 code |
| description | TEXT | Diagnosis description |
| diagnosed_by_name | VARCHAR(255) | Diagnosing clinician |
| diagnosed_at | TIMESTAMPTZ | Diagnosis time |
| is_primary | BOOLEAN | Primary diagnosis flag |
| notes | TEXT | Notes |

#### 4.3.10 investigations

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| investigation_type | VARCHAR(30) | LABORATORY, RADIOLOGY, ECG, CT_SCAN, etc. |
| test_name | VARCHAR(255) | Test name |
| ordered_by_name | VARCHAR(255) | Ordering clinician |
| ordered_at | TIMESTAMPTZ | Order time |
| specimen_collected_at | TIMESTAMPTZ | Collection time |
| resulted_at | TIMESTAMPTZ | Result time |
| result | TEXT | Result text |
| is_abnormal | BOOLEAN | Abnormal flag |
| is_critical | BOOLEAN | Critical flag |
| status | VARCHAR(25) | ORDERED, SPECIMEN_COLLECTED, IN_PROGRESS, RESULTED, CANCELLED |
| priority | VARCHAR(20) | Priority level |
| notes | TEXT | Notes |

#### 4.3.11 clinical_notes

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| note_type | VARCHAR(40) | See NoteType enum |
| content | TEXT | Note content |
| recorded_by_name | VARCHAR(255) | Author |
| recorded_at | TIMESTAMPTZ | Recording time |
| section | VARCHAR(100) | Section heading |

**Note Types:** PHYSICAL_FINDINGS, PROGRESS_NOTE, NURSING_NOTE, DOCTOR_NOTE, TRIAGE_NOTE, HISTORY_OF_PRESENTING_COMPLAINT, PAST_MEDICAL_HISTORY, SOCIAL_HISTORY, FAMILY_HISTORY, REVIEW_OF_SYSTEMS, ALLERGIES, CURRENT_MEDICATIONS, TREATMENT_PLAN, DISCHARGE_SUMMARY, HANDOVER, OTHER

#### 4.3.12 iot_devices
IoT device registry. Authenticated via unique API key.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| serial_number | VARCHAR(100) | UNIQUE serial number |
| device_name | VARCHAR(100) | Display name |
| device_type | VARCHAR(30) | ESP32_MONITOR, PULSE_OXIMETER, ECG_MONITOR, etc. |
| hospital_id | UUID | FK -> hospitals |
| api_key | VARCHAR(255) | UNIQUE API key for auth |
| status | VARCHAR(20) | REGISTERED, ONLINE, OFFLINE, MONITORING, ERROR, DECOMMISSIONED |
| firmware_version | VARCHAR(30) | Firmware version |
| last_heartbeat_at | TIMESTAMPTZ | Last heartbeat |
| last_data_at | TIMESTAMPTZ | Last data received |
| battery_level | INTEGER | 0-100% |
| wifi_rssi | INTEGER | WiFi signal (dBm) |
| ip_address | VARCHAR(45) | Device IP |
| mac_address | VARCHAR(17) | MAC address |
| location | VARCHAR(100) | Physical location |
| heartbeat_timeout_seconds | INTEGER | DEFAULT 30 |
| data_interval_seconds | INTEGER | DEFAULT 5 |

#### 4.3.13 device_sessions
Links a device to a visit for continuous monitoring.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| device_id | UUID | FK -> iot_devices |
| visit_id | UUID | FK -> visits |
| started_at | TIMESTAMPTZ | Session start |
| ended_at | TIMESTAMPTZ | Session end (null if active) |
| session_active | BOOLEAN | Active flag |
| started_by_name | VARCHAR(255) | Who started |
| ended_by_name | VARCHAR(255) | Who ended |
| end_reason | VARCHAR(255) | End reason |
| total_readings | BIGINT | Total data points |
| rejected_readings | BIGINT | Rejected data points |
| alerts_generated | INTEGER | Alerts triggered |
| retriages_triggered | INTEGER | Re-triages triggered |

#### 4.3.14 vital_streams
High-frequency IoT vital data (every 5 seconds).

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| device_id | VARCHAR(100) | Source device |
| session_id | UUID | FK -> device_sessions |
| captured_at | TIMESTAMPTZ | Device capture time |
| received_at | TIMESTAMPTZ | Server receive time |
| heart_rate | INTEGER | BPM |
| spo2 | INTEGER | Percentage |
| respiratory_rate | INTEGER | Breaths/min |
| temperature | DOUBLE PRECISION | Celsius |
| systolic_bp | INTEGER | mmHg |
| diastolic_bp | INTEGER | mmHg |
| blood_glucose | DOUBLE PRECISION | mmol/L |
| ecg_waveform | TEXT | Raw ECG data |
| ecg_rhythm | VARCHAR(30) | Rhythm classification |
| ecg_qrs_duration | INTEGER | QRS duration (ms) |
| ecg_st_deviation | DOUBLE PRECISION | ST deviation (mm) |
| signal_quality | VARCHAR(15) | GOOD, ACCEPTABLE, POOR, INVALID, UNKNOWN |
| spo2_perfusion_index | DOUBLE PRECISION | PI value |
| is_validated | BOOLEAN | Data validated flag |
| rejection_reason | VARCHAR(255) | If rejected |
| battery_level | INTEGER | Device battery |
| wifi_rssi | INTEGER | WiFi signal |
| sequence_number | BIGINT | Sequence counter |

#### 4.3.15 shift_assignments
ED zone-based shift management.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| hospital_id | UUID | FK -> hospitals |
| shift_date | DATE | Shift date |
| shift_period | VARCHAR(20) | DAY (07:00-19:00), NIGHT (19:00-07:00) |
| user_id | UUID | FK -> users |
| zone | VARCHAR(20) | RESUS, ACUTE, GENERAL, TRIAGE, OBSERVATION, ISOLATION, PEDIATRIC |
| shift_function | VARCHAR(30) | CHARGE_NURSE, TRIAGE_NURSE, ZONE_NURSE, PRIMARY_DOCTOR, etc. |
| started_at | TIMESTAMPTZ | Actual start |
| ended_at | TIMESTAMPTZ | Actual end |

**Constraint:** UNIQUE (user_id, shift_date, shift_period)

#### 4.3.16 sepsis_screenings

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| screened_at | TIMESTAMP | Screening time |
| sepsis_status | VARCHAR(20) | NO_SEPSIS, SIRS_POSITIVE, SEPSIS_SUSPECTED, SEVERE_SEPSIS, SEPTIC_SHOCK |
| qsofa_score | INTEGER | Quick SOFA (0-3) |
| altered_mentation | BOOLEAN | qSOFA: GCS < 15 |
| respiratory_rate_high | BOOLEAN | qSOFA: RR >= 22 |
| systolic_bp_low | BOOLEAN | qSOFA: SBP <= 100 |
| sirs_score | INTEGER | SIRS criteria met (0-4) |
| temperature_criteria_met | BOOLEAN | Temp > 38.3 or < 36 |
| heart_rate_criteria_met | BOOLEAN | HR > 90 |
| respiratory_rate_criteria_met | BOOLEAN | RR > 20 |
| wbc_criteria_met | BOOLEAN | WBC > 12k or < 4k |
| suspected_infection_source | TEXT | Source description |
| lactate_level | DOUBLE PRECISION | Lactate mmol/L |
| **1-Hour Bundle** | | |
| bundle_started_at | TIMESTAMP | Bundle start |
| bundle_completed_at | TIMESTAMP | Bundle complete |
| blood_culture_obtained | BOOLEAN | Cultures drawn |
| broad_spectrum_antibiotics | BOOLEAN | Antibiotics given |
| iv_crystalloid_bolus | BOOLEAN | 30ml/kg bolus |
| lactate_measured | BOOLEAN | Lactate measured |
| vasopressors_if_needed | BOOLEAN | Vasopressors if MAP < 65 |
| repeat_lactate_if_elevated | BOOLEAN | Repeat lactate if > 2 |

#### 4.3.17 fast_track_activations
Time-critical pathway tracking (Stroke, STEMI).

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| fast_track_type | VARCHAR(30) | STROKE_SUSPECTED, STEMI_SUSPECTED, NSTEMI_SUSPECTED, TIA_SUSPECTED |
| status | VARCHAR(30) | ACTIVATED through COMPLETED |
| activated_at | TIMESTAMP | Protocol activation |
| symptom_onset_time | TIMESTAMP | Symptom onset |
| **Stroke Fields** | | |
| be_fast_score | VARCHAR(255) | BE-FAST assessment |
| nihss_score | INTEGER | NIH Stroke Scale |
| ct_ordered_at | TIMESTAMP | CT order time |
| ct_completed_at | TIMESTAMP | CT complete |
| ct_result | TEXT | CT findings |
| is_hemorrhagic | BOOLEAN | Hemorrhagic stroke |
| thrombolysis_eligible | BOOLEAN | tPA eligible |
| thrombolysis_started_at | TIMESTAMP | tPA start |
| door_to_ct_minutes | INTEGER | Door-to-CT time |
| **Cardiac Fields** | | |
| ecg_ordered_at | TIMESTAMP | ECG order time |
| ecg_completed_at | TIMESTAMP | ECG complete |
| ecg_result | TEXT | ECG findings |
| st_elevation | BOOLEAN | STEMI flag |
| troponin_ordered | BOOLEAN | Troponin ordered |
| troponin_result | DOUBLE PRECISION | Troponin value |
| aspirin_given | BOOLEAN | Aspirin given |
| referred_for_pci | BOOLEAN | PCI referral |
| door_to_ecg_minutes | INTEGER | Door-to-ECG time |
| door_to_needle_minutes | INTEGER | Door-to-needle time |

#### 4.3.18 hypoglycemia_events

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| detected_at | TIMESTAMP | Detection time |
| glucose_level | DOUBLE PRECISION | Glucose (mmol/L) |
| trigger_reason | VARCHAR(255) | How detected |
| severity | VARCHAR(20) | Severity level |
| treatment_given | TEXT | Treatment administered |
| treatment_given_at | TIMESTAMP | Treatment time |
| repeat_glucose_level | DOUBLE PRECISION | Follow-up glucose |
| repeat_glucose_at | TIMESTAMP | Follow-up time |
| resolved | BOOLEAN | Resolution flag |
| resolved_at | TIMESTAMP | Resolution time |

#### 4.3.19 infection_screenings

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| risk_level | VARCHAR(20) | CONFIRMED, HIGH_RISK, MODERATE_RISK, LOW_RISK, CLEARED |
| isolation_type | VARCHAR(20) | AIRBORNE, DROPLET, CONTACT, STRICT, PROTECTIVE |
| suspected_condition | VARCHAR(255) | Suspected infection |
| notifiable_disease | VARCHAR(30) | Rwanda notifiable disease |
| **Screening Questions** | | |
| has_fever | BOOLEAN | Fever present |
| has_cough | BOOLEAN | Cough |
| has_cough_duration_weeks | INTEGER | Cough duration |
| has_night_sweats | BOOLEAN | Night sweats |
| has_weight_loss | BOOLEAN | Weight loss |
| has_rash | BOOLEAN | Rash |
| has_diarrhea | BOOLEAN | Diarrhea |
| has_recent_travel | BOOLEAN | Recent travel |
| has_contact_with_infectious | BOOLEAN | Infectious contact |
| has_bleeding_symptoms | BOOLEAN | Bleeding |
| **PPE Requirements** | | |
| requires_n95 | BOOLEAN | N95 mask |
| requires_gown | BOOLEAN | Gown |
| requires_gloves | BOOLEAN | Gloves |
| requires_face_shield | BOOLEAN | Face shield |
| requires_apron | BOOLEAN | Apron |
| requires_boot_covers | BOOLEAN | Boot covers |
| **Isolation Management** | | |
| isolation_room_assigned | VARCHAR(255) | Room number |
| isolation_started_at | TIMESTAMP | Isolation start |
| isolation_ended_at | TIMESTAMP | Isolation end |
| public_health_notified_at | TIMESTAMP | MOH notification |
| public_health_reference_number | VARCHAR(255) | Reference number |

**Notifiable Diseases:** TUBERCULOSIS, CHOLERA, MEASLES, EBOLA, MARBURG, COVID_19, MENINGOCOCCAL, YELLOW_FEVER, RABIES, PLAGUE, TYPHOID, MALARIA_SEVERE, DENGUE, HEPATITIS_A/B/E, HIV_NEW_DIAGNOSIS, MPOX, AVIAN_INFLUENZA, ANTHRAX, OTHER_NOTIFIABLE

#### 4.3.20 clinical_documents
Structured clinical documentation with amendment tracking.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| document_type | VARCHAR(30) | INITIAL_ASSESSMENT, PROGRESS_NOTE, etc. |
| title | VARCHAR(255) | Document title |
| content | TEXT | Document content |
| author_name | VARCHAR(255) | Author |
| author_role | VARCHAR(255) | Author's role |
| author_license_number | VARCHAR(50) | License number |
| signed_at | TIMESTAMP | Signature time |
| is_signed | BOOLEAN | Signed flag |
| co_signed_by_name | VARCHAR(255) | Co-signer |
| co_signed_at | TIMESTAMP | Co-sign time |
| is_amendment | BOOLEAN | Amendment flag |
| amendment_reason | TEXT | Amendment reason |
| original_document_id | UUID | FK -> clinical_documents |

#### 4.3.21 drug_formularies
Rwanda Essential Medicines List (REML) + hospital formulary.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| generic_name | VARCHAR(255) | Generic drug name |
| brand_names | TEXT | Brand names |
| drug_class | VARCHAR(255) | Drug class |
| atc_code | VARCHAR(20) | ATC classification |
| reml_category | VARCHAR(255) | REML category |
| adult_min_dose_mg | DOUBLE PRECISION | Adult min dose |
| adult_max_dose_mg | DOUBLE PRECISION | Adult max dose |
| adult_max_daily_dose_mg | DOUBLE PRECISION | Adult max daily |
| pediatric_min_dose_mg_per_kg | DOUBLE PRECISION | Peds min per kg |
| pediatric_max_dose_mg_per_kg | DOUBLE PRECISION | Peds max per kg |
| pediatric_max_daily_dose_mg_per_kg | DOUBLE PRECISION | Peds max daily per kg |
| geriatric_adjustment_percent | DOUBLE PRECISION | Geriatric dose adjustment |
| renal_adjustment_required | BOOLEAN | Renal dose adjustment |
| hepatic_adjustment_required | BOOLEAN | Hepatic dose adjustment |
| available_routes | VARCHAR(255) | Valid routes |
| contraindications | TEXT | Contraindications |
| major_interactions | TEXT | Drug interactions |
| allergen_groups | TEXT | Allergen groups |
| is_high_alert | BOOLEAN | High-alert medication |
| requires_double_check | BOOLEAN | Requires double-check |
| black_box_warning | TEXT | Black box warning |
| pregnancy_category | VARCHAR(5) | Pregnancy category |
| is_on_reml | BOOLEAN | On Rwanda REML |
| hospital_id | UUID | FK -> hospitals (null = national) |

#### 4.3.22 medication_safety_checks

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| medication_id | UUID | FK -> medication_administrations |
| drug_name | VARCHAR(255) | Drug checked |
| prescribed_dose_mg | DOUBLE PRECISION | Dose |
| patient_weight_kg | DOUBLE PRECISION | Patient weight |
| allergy_check_passed | BOOLEAN | Allergy safe |
| allergy_warning | TEXT | Allergy warning |
| dose_check_passed | BOOLEAN | Dose in range |
| dose_warning | TEXT | Dose warning |
| interaction_check_passed | BOOLEAN | No interactions |
| interaction_warning | TEXT | Interaction warning |
| duplicate_therapy_check_passed | BOOLEAN | No duplicates |
| duplicate_warning | TEXT | Duplicate warning |
| overall_safe | BOOLEAN | Overall safety |
| overridden_by | VARCHAR(255) | Override clinician |
| override_reason | TEXT | Override reason |

#### 4.3.23 lab_orders
Full lab order lifecycle with critical value notification.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| order_number | VARCHAR(30) | UNIQUE order number |
| test_name | VARCHAR(255) | Test name |
| test_code | VARCHAR(50) | Test code |
| priority | VARCHAR(15) | STAT (30min), URGENT (120min), ROUTINE (1440min) |
| ordered_at | TIMESTAMP | Order time |
| specimen_type | VARCHAR(50) | Specimen type |
| specimen_collected_at | TIMESTAMP | Collection time |
| received_by_lab_at | TIMESTAMP | Lab received |
| processing_started_at | TIMESTAMP | Processing start |
| resulted_at | TIMESTAMP | Result time |
| result_value | TEXT | Result text |
| result_unit | VARCHAR(50) | Result unit |
| result_numeric | DOUBLE PRECISION | Numeric result |
| reference_range_min | DOUBLE PRECISION | Ref range low |
| reference_range_max | DOUBLE PRECISION | Ref range high |
| is_abnormal | BOOLEAN | Abnormal flag |
| is_critical | BOOLEAN | Critical flag |
| critical_value_type | VARCHAR(30) | Critical value type |
| critical_value_notified_at | TIMESTAMP | Notification time |
| critical_value_notified_to | VARCHAR(255) | Notified clinician |
| critical_value_acknowledged_at | TIMESTAMP | Acknowledgement |
| turnaround_minutes | INTEGER | TAT in minutes |

**Critical Value Types:** POTASSIUM_HIGH/LOW, SODIUM_HIGH/LOW, GLUCOSE_HIGH/LOW, HEMOGLOBIN_LOW, PLATELET_LOW, WBC_HIGH/LOW, CREATININE_HIGH, LACTATE_HIGH, TROPONIN_HIGH, INR_HIGH, PH_LOW/HIGH, MALARIA_POSITIVE, OTHER_CRITICAL

#### 4.3.24-27 Clinical Pathways (4 tables)

**clinical_pathways** - Protocol definitions (pathway_code, pathway_name, category, target_population, source_guideline)

**pathway_steps** - Ordered steps within a pathway (step_order, step_title, step_description, timeframe_minutes, is_mandatory)

**pathway_activations** - Patient-level pathway instances (visit_id, pathway_id, status: ACTIVE/COMPLETED/ABANDONED/DEVIATED)

**pathway_step_completions** - Step completion tracking (activation_id, step_id, completed_at, was_skipped, skip_reason, time_to_complete_minutes)

**Pathway Categories:** MALARIA, TRAUMA, RESPIRATORY, CARDIAC, NEUROLOGICAL, OBSTETRIC, PEDIATRIC, INFECTIOUS_DISEASE, SURGICAL, POISONING, BURNS, SNAKEBITE, OTHER

#### 4.3.28 icu_escalations

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| escalation_reason | TEXT | Reason for ICU |
| trigger_type | VARCHAR(30) | HEMODYNAMIC_INSTABILITY, RESPIRATORY_FAILURE, etc. |
| status | VARCHAR(20) | REQUESTED, ICU_NOTIFIED, ICU_ACCEPTED, ICU_DECLINED, TRANSFERRED_TO_ICU, STABILIZING, CANCELLED |
| icu_team_notified_at | TIMESTAMP | ICU team notification |
| icu_consultant | VARCHAR(255) | ICU consultant name |
| icu_responded_at | TIMESTAMP | Response time |
| icu_response_minutes | INTEGER | Response time (min) |
| icu_bed_available | BOOLEAN | Bed availability |
| icu_bed_number | VARCHAR(50) | Bed number |
| intubation_required | BOOLEAN | Intubation needed |
| vasopressors_required | BOOLEAN | Vasopressors needed |
| mechanical_ventilation | BOOLEAN | Ventilation needed |

#### 4.3.29 referrals
Inter-hospital referral with transport tracking.

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| referral_type | VARCHAR(25) | UPWARD/LATERAL/DOWNWARD/COUNTER/EMERGENCY_TRANSFER |
| status | VARCHAR(35) | INITIATED through COMPLETED |
| referring_hospital_id | UUID | FK -> hospitals |
| referring_clinician | VARCHAR(255) | Referring doctor |
| receiving_hospital_name | VARCHAR(255) | Destination hospital |
| referral_reason | TEXT | Reason |
| clinical_summary | TEXT | Clinical summary |
| current_triage_category | VARCHAR(10) | Triage category |
| **Pre-Transfer Checklist** | | |
| airway_secured | BOOLEAN | Airway secured |
| breathing_stable | BOOLEAN | Breathing stable |
| circulation_stable | BOOLEAN | Circulation stable |
| iv_access_established | BOOLEAN | IV access |
| medications_documented | BOOLEAN | Meds documented |
| allergies_documented | BOOLEAN | Allergies documented |
| consent_obtained | BOOLEAN | Consent obtained |
| **Transport** | | |
| transport_mode | VARCHAR(20) | AMBULANCE_SAMU, HOSPITAL_AMBULANCE, etc. |
| escort_required | BOOLEAN | Escort needed |
| escort_name | VARCHAR(255) | Escort |
| estimated_transfer_time_minutes | INTEGER | Estimated time |
| departed_at | TIMESTAMP | Departure |
| arrived_at | TIMESTAMP | Arrival |
| actual_transfer_time_minutes | INTEGER | Actual time |
| **Rwanda Integration** | | |
| rhmis_case_number | VARCHAR(50) | RHMIS reference |
| samu_request_number | VARCHAR(50) | SAMU reference |

#### 4.3.30 safety_incidents

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| hospital_id | UUID | FK -> hospitals |
| visit_id | UUID | FK -> visits (optional) |
| incident_number | VARCHAR(20) | UNIQUE number |
| incident_type | VARCHAR(35) | MEDICATION_ERROR, DIAGNOSTIC_ERROR, etc. |
| severity | VARCHAR(20) | NEAR_MISS, NO_HARM, MILD/MODERATE/SEVERE_HARM, DEATH |
| status | VARCHAR(35) | REPORTED through CLOSED |
| description | TEXT | Incident description |
| root_cause_analysis | TEXT | RCA findings |
| corrective_action | TEXT | Corrective action |
| preventive_measures | TEXT | Preventive measures |
| is_anonymous | BOOLEAN | Anonymous report |

#### 4.3.31 handover_reports

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| visit_id | UUID | FK -> visits |
| hospital_id | UUID | FK -> hospitals |
| report_type | VARCHAR(30) | SHIFT_HANDOVER, WARD_TRANSFER, etc. |
| patient_summary | TEXT | Patient summary |
| triage_summary | TEXT | Triage summary |
| vital_signs_trend | TEXT | Vital trends |
| active_clinical_alerts | TEXT | Active alerts |
| outstanding_tasks | TEXT | Pending tasks |
| plan_of_care | TEXT | Care plan |
| received_by_name | VARCHAR(255) | Receiver |
| is_acknowledged | BOOLEAN | Acknowledged |

#### 4.3.32 offline_sync_records

| Column | Type | Description |
|--------|------|-------------|
| id | UUID | Primary key |
| hospital_id | UUID | FK -> hospitals |
| client_device_id | VARCHAR(255) | Client device |
| entity_type | VARCHAR(50) | Entity being synced |
| entity_id | UUID | Entity ID |
| operation_type | VARCHAR(10) | CREATE, UPDATE |
| payload | TEXT | JSON payload |
| sync_status | VARCHAR(15) | PENDING, SYNCED, CONFLICT, FAILED |
| conflict_resolution | TEXT | Resolution strategy |

#### 4.3.33 Additional Tables

- **system_health_statuses** - Hospital system health monitoring (server, database, internet, power status)
- **quality_metric_snapshots** - KPI snapshots (period-based metrics)
- **surge_predictions** - ED surge risk predictions
- **moh_reports** - Ministry of Health report submissions
- **clinical_policies** - Governance policy definitions
- **policy_audit_logs** - Policy change audit trail

---

## 5. Data Dictionary

### 5.1 Complete Enum Reference

#### Clinical Enums

| Enum | Values | Description |
|------|--------|-------------|
| TriageCategory | RED (severity:4, wait:0min), ORANGE (3, 10min), YELLOW (2, 30min), GREEN (1, 60min), BLUE (0, dead) | SATS triage categories |
| AvpuScore | ALERT(0pts), CONFUSED(1pt), VERBAL(1pt), PAIN(2pts), UNRESPONSIVE(3pts) | TEWS consciousness component |
| MobilityStatus | WALKING(0pts), WITH_HELP(1pt), STRETCHER(2pts) | TEWS mobility component |
| TraumaStatus | NO_TRAUMA(0pts), TRAUMA(1pt) | TEWS trauma component |
| VisitStatus | REGISTERED, AWAITING_TRIAGE, TRIAGED, AWAITING_ASSESSMENT, UNDER_ASSESSMENT, UNDER_TREATMENT, UNDER_OBSERVATION, PENDING_DISPOSITION, DISCHARGED, ADMITTED, TRANSFERRED, ICU_ADMITTED, LEFT_WITHOUT_BEING_SEEN, DECEASED | Visit lifecycle |
| DispositionType | DISCHARGED_HOME, ADMITTED_TO_WARD, ICU_ADMISSION, TRANSFERRED, LEFT_AGAINST_MEDICAL_ADVICE, LEFT_WITHOUT_BEING_SEEN, DECEASED | Patient outcome |
| ArrivalMode | WALK_IN, AMBULANCE, REFERRAL, POLICE, HELICOPTER, OTHER | How patient arrived |
| Gender | MALE, FEMALE, OTHER, UNKNOWN | Patient gender |

#### Investigation & Diagnosis Enums

| Enum | Values |
|------|--------|
| InvestigationType | LABORATORY, RADIOLOGY, ECG, ULTRASOUND, CT_SCAN, MRI, XRAY, BLOOD_GAS, URINALYSIS, RAPID_TEST, POINT_OF_CARE, OTHER |
| InvestigationStatus | ORDERED, SPECIMEN_COLLECTED, IN_PROGRESS, RESULTED, CANCELLED |
| DiagnosisType | PROVISIONAL, CONFIRMED, DIFFERENTIAL, WORKING |
| NoteType | PHYSICAL_FINDINGS, PROGRESS_NOTE, NURSING_NOTE, DOCTOR_NOTE, TRIAGE_NOTE, HPC, PMH, SH, FH, ROS, ALLERGIES, CURRENT_MEDICATIONS, TREATMENT_PLAN, DISCHARGE_SUMMARY, HANDOVER, OTHER |

#### Medication Enums

| Enum | Values |
|------|--------|
| MedicationRoute | PO, IV, IM, SC, SL, PR, INH, NEB, TOP, NASAL, OPHTHALMIC, OTIC, ETT, IO, OTHER |
| MedicationStatus | PRESCRIBED, ADMINISTERED, HELD, REFUSED, CANCELLED |

#### Alert Enums

| Enum | Values |
|------|--------|
| AlertType | 30 types (see clinical_alerts section) |
| AlertSeverity | CRITICAL, HIGH, MEDIUM, LOW, INFO |
| DeteriorationPattern | SINGLE_VITAL_CRITICAL, MULTI_VITAL_TREND, RAPID_DECLINE, SUSTAINED_ABNORMALITY, SPO2_OVERRIDE, SEPSIS_PATTERN, RESPIRATORY_FAILURE_PATTERN, HEMODYNAMIC_INSTABILITY, DEVICE_DISCONNECTED, NONE |

#### IoT & Device Enums

| Enum | Values |
|------|--------|
| DeviceType | ESP32_MONITOR, PULSE_OXIMETER, ECG_MONITOR, BP_MONITOR, TEMPERATURE_PROBE, GLUCOMETER, AMBULANCE_MONITOR, OTHER |
| DeviceStatus | REGISTERED, ONLINE, OFFLINE, MONITORING, ERROR, DECOMMISSIONED |
| VitalSource | MANUAL_ENTRY, IOT_DEVICE, AMBULANCE_MONITOR, IMPORTED |
| SignalQuality | GOOD, ACCEPTABLE, POOR, INVALID, UNKNOWN |

#### Role & Shift Enums

| Enum | Values |
|------|--------|
| Role | SUPER_ADMIN, HOSPITAL_ADMIN, DOCTOR, TRIAGE_NURSE, NURSE, REGISTRAR, PARAMEDIC, LAB_TECHNICIAN, READ_ONLY |
| Designation | ED_HEAD, CONSULTANT, SENIOR_MEDICAL_OFFICER, MEDICAL_OFFICER, RESIDENT, INTERN, NURSE_MANAGER, CHARGE_NURSE, SENIOR_NURSE, STAFF_NURSE, STUDENT_NURSE, HEAD_LAB_TECHNICIAN, LAB_TECHNICIAN, SENIOR_REGISTRAR, REGISTRAR, SENIOR_PARAMEDIC, PARAMEDIC, UNSPECIFIED |
| EdZone | RESUS, ACUTE, GENERAL, TRIAGE, OBSERVATION, ISOLATION, PEDIATRIC |
| ShiftPeriod | DAY (07:00-19:00), NIGHT (19:00-07:00) |
| ShiftFunction | CHARGE_NURSE, TRIAGE_NURSE, ZONE_NURSE, PRIMARY_DOCTOR, SUPERVISING_DOCTOR, RESIDENT |

#### Clinical Protocol Enums

| Enum | Values |
|------|--------|
| SepsisStatus | NO_SEPSIS, SIRS_POSITIVE, SEPSIS_SUSPECTED, SEVERE_SEPSIS, SEPTIC_SHOCK |
| FastTrackType | STROKE_SUSPECTED, STEMI_SUSPECTED, NSTEMI_SUSPECTED, TIA_SUSPECTED |
| FastTrackStatus | ACTIVATED, ECG_ORDERED, ECG_COMPLETED, CT_ORDERED, CT_COMPLETED, THROMBOLYSIS_CONSIDERED, INTERVENTION_STARTED, TRANSFERRED_FOR_PCI, COMPLETED, CANCELLED |
| IsolationType | AIRBORNE, DROPLET, CONTACT, STRICT, PROTECTIVE |
| InfectionRiskLevel | CONFIRMED, HIGH_RISK, MODERATE_RISK, LOW_RISK, CLEARED |
| NotifiableDisease | TUBERCULOSIS, CHOLERA, MEASLES, EBOLA, MARBURG, COVID_19, MENINGOCOCCAL, YELLOW_FEVER, RABIES, PLAGUE, TYPHOID, MALARIA_SEVERE, DENGUE, HEPATITIS_A/B/E, HIV_NEW_DIAGNOSIS, MPOX, AVIAN_INFLUENZA, ANTHRAX, OTHER_NOTIFIABLE |
| PathwayCategory | MALARIA, TRAUMA, RESPIRATORY, CARDIAC, NEUROLOGICAL, OBSTETRIC, PEDIATRIC, INFECTIOUS_DISEASE, SURGICAL, POISONING, BURNS, SNAKEBITE, OTHER |
| IcuTriggerType | HEMODYNAMIC_INSTABILITY, RESPIRATORY_FAILURE, DECREASED_CONSCIOUSNESS, SEPTIC_SHOCK, POST_CARDIAC_ARREST, STATUS_EPILEPTICUS, MASSIVE_HEMORRHAGE, MULTI_ORGAN_DYSFUNCTION, POST_OPERATIVE, CLINICAL_JUDGEMENT |
| LabPriority | STAT (30min), URGENT (120min), ROUTINE (1440min) |
| CriticalValueType | POTASSIUM_HIGH/LOW, SODIUM_HIGH/LOW, GLUCOSE_HIGH/LOW, HEMOGLOBIN_LOW, PLATELET_LOW, WBC_HIGH/LOW, CREATININE_HIGH, LACTATE_HIGH, TROPONIN_HIGH, INR_HIGH, PH_LOW/HIGH, MALARIA_POSITIVE, OTHER_CRITICAL |

#### Referral & Transfer Enums

| Enum | Values |
|------|--------|
| ReferralType | UPWARD_REFERRAL, LATERAL_REFERRAL, DOWNWARD_REFERRAL, COUNTER_REFERRAL, EMERGENCY_TRANSFER |
| ReferralStatus | INITIATED, RECEIVING_FACILITY_CONTACTED, ACCEPTED, DECLINED, PATIENT_STABILIZED, IN_TRANSIT, RECEIVED_AT_DESTINATION, COMPLETED, CANCELLED |
| TransportMode | AMBULANCE_SAMU, HOSPITAL_AMBULANCE, PRIVATE_VEHICLE, HELICOPTER, OTHER |

#### Administration & Governance Enums

| Enum | Values |
|------|--------|
| IncidentType | MEDICATION_ERROR, DIAGNOSTIC_ERROR, DELAYED_TREATMENT, WRONG_PATIENT, FALL, EQUIPMENT_FAILURE, COMMUNICATION_FAILURE, DOCUMENTATION_ERROR, TRIAGE_ERROR, MISSED_DIAGNOSIS, ALLERGIC_REACTION, PROCEDURAL_COMPLICATION, BLOOD_TRANSFUSION_ERROR, INFECTION_RELATED, PATIENT_IDENTIFICATION_ERROR, OTHER |
| IncidentSeverity | NEAR_MISS, NO_HARM, MILD_HARM, MODERATE_HARM, SEVERE_HARM, DEATH |
| HandoverReportType | SHIFT_HANDOVER, WARD_TRANSFER, DISCHARGE_SUMMARY, ICU_TRANSFER, INTER_HOSPITAL_TRANSFER |
| MohReportType | DAILY_SUMMARY, WEEKLY_SURVEILLANCE, MONTHLY_STATISTICS, QUARTERLY_REVIEW, ANNUAL_REPORT, OUTBREAK_NOTIFICATION, MORTALITY_REVIEW |
| PolicyType | TRIAGE_RULE, DRUG_PROTOCOL, CLINICAL_GUIDELINE, INFECTION_CONTROL, REFERRAL_CRITERIA, STAFFING_REQUIREMENT, EQUIPMENT_PROTOCOL, QUALITY_STANDARD, CONSENT_FORM, DISCHARGE_CRITERIA, OTHER |
| MetricPeriod | DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY |
| SurgeRiskLevel | LOW, MODERATE, HIGH, CRITICAL |

### 5.2 TEWS Scoring Reference

**Triage Early Warning Score (TEWS)** components and points:

| Vital | 0 | 1 | 2 | 3 |
|-------|---|---|---|---|
| Heart Rate | 51-100 | 101-110 or 41-50 | 111-129 or <41 | >= 130 |
| Respiratory Rate | 9-14 | 15-20 | 21-29 | >= 30 or <= 8 |
| Systolic BP | 100-199 | 80-99 | 70-79 or >= 200 | <70 |
| Temperature | 35.0-38.4 | 34.0-34.9 | 33.0-33.9 or >= 38.5 | <33.0 or >= 41.0 |
| AVPU | Alert (0) | Confused (1), Verbal (1) | Pain (2) | Unresponsive (3) |
| Mobility | Walking (0) | With help (1) | Stretcher (2) | - |
| Trauma | No trauma (0) | Trauma (1) | - | - |

**Category Assignment:**
- TEWS 0-2: GREEN (Standard, 60 min target)
- TEWS 3-4: YELLOW (Urgent, 30 min target)
- TEWS 5-6: ORANGE (Very Urgent, 10 min target)
- TEWS >= 7: RED (Emergency, immediate)
- Any Emergency Sign present: RED (override)
- Any Very Urgent Sign present: ORANGE (minimum)
- Any Urgent Sign present: YELLOW (minimum)
- BLUE: Dead on arrival

---

## 6. Backend Architecture

### 6.1 Module Structure

```
com.smartTriage.smartTriage_server
├── config/
│   ├── SecurityConfig.java          # JWT + RBAC security
│   ├── WebSocketConfig.java         # STOMP WebSocket
│   ├── CorsConfig.java              # CORS configuration
│   ├── PasswordEncoderConfig.java   # BCrypt
│   └── JacksonConfig.java           # JSON serialization
├── security/
│   ├── JwtAuthenticationFilter.java # JWT filter
│   ├── JwtTokenProvider.java        # Token generation/validation
│   ├── JwtAuthenticationEntryPoint.java
│   └── IoTApiKeyFilter.java         # IoT device API key auth
├── shared/
│   ├── entity/BaseEntity.java       # Common audit fields
│   ├── dto/ApiResponse.java         # Standard response wrapper
│   └── exception/                   # Global exception handling
├── module/
│   ├── auth/                        # Authentication (login, refresh)
│   ├── hospital/                    # Hospital management
│   ├── user/                        # User management + designations
│   ├── patient/                     # Patient registration
│   ├── visit/                       # Visit lifecycle
│   ├── vitals/                      # Vital signs recording
│   ├── triage/                      # Triage assessment + TEWS
│   ├── alert/                       # Alert generation + escalation
│   ├── medication/                  # Medication administration
│   ├── diagnosis/                   # Diagnosis management
│   ├── investigation/               # Investigation orders
│   ├── clinicalnote/                # Clinical notes
│   ├── iot/                         # IoT device + streaming
│   ├── shift/                       # Shift assignments
│   ├── retriage/                    # Dynamic re-triage engine
│   ├── sepsis/                      # Sepsis screening
│   ├── fasttrack/                   # Fast-track protocols
│   ├── hypoglycemia/                # Hypoglycemia management
│   ├── isolation/                   # Infection isolation
│   ├── documentation/               # Clinical documentation
│   ├── medsafety/                   # Medication safety
│   ├── lab/                         # Lab orders + results
│   ├── pathway/                     # Clinical pathways
│   ├── icu/                         # ICU escalation
│   ├── referral/                    # Inter-hospital referral
│   ├── handover/                    # Shift handover
│   ├── safety/                      # Safety incidents
│   ├── quality/                     # Quality metrics
│   ├── prediction/                  # Surge prediction
│   ├── reporting/                   # MOH reports
│   ├── governance/                  # Policy management
│   └── offline/                     # Offline sync
```

### 6.2 Key Backend Services

#### Alert Engine (AlertGenerationService)
- Monitors vital signs for threshold breaches
- Generates zone-aware alerts with SATS target times
- Escalation tiers: Tier 1 (zone nurse) -> Tier 2 (zone doctor) -> Tier 3 (ED head)
- Broadcasts via WebSocket to relevant subscribers

#### Dynamic Re-triage (DynamicRetriageService)
- Analyzes vital trends for deterioration patterns
- 7 pattern types: single critical, multi-vital trend, rapid decline, sustained abnormality, SpO2 override, sepsis pattern, respiratory failure
- Automatic category escalation/de-escalation
- Generates triage records with is_system_triggered = true

#### IoT Stream Processing (VitalStreamService)
- Validates incoming device data (signal quality, physiologic range)
- Stores to vital_streams table
- Creates periodic vital_signs snapshots
- Triggers alert generation on threshold breaches
- Updates device session counters

### 6.3 Security Architecture

```
Request Flow:
  Client → [CORS Filter] → [JWT Auth Filter / IoT API Key Filter]
    → [Spring Security] → [Method Security @PreAuthorize]
      → Controller → Service → Repository → Database

Authentication Methods:
  1. JWT Bearer Token (for all user requests)
     - Access token: 15 minutes
     - Refresh token: 24 hours
     - BCrypt password hashing

  2. API Key Header (for IoT devices only)
     - Header: X-Device-API-Key
     - Per-device unique key
     - Only for /api/v1/iot/stream/** endpoints
```

---

## 7. Frontend Architecture

### 7.1 Project Structure

```
SmartTriage_Frontend_V6/src/
├── api/                    # API client + endpoint modules
│   ├── client.ts           # Axios-like HTTP client with JWT
│   ├── types.ts            # API response types
│   ├── websocket.ts        # STOMP WebSocket client
│   ├── auth.ts             # Auth endpoints
│   ├── patients.ts         # Patient endpoints
│   ├── visits.ts           # Visit endpoints
│   ├── vitals.ts           # Vital endpoints
│   ├── triage.ts           # Triage endpoints
│   ├── alerts.ts           # Alert endpoints
│   ├── iot.ts              # IoT device endpoints
│   └── ... (30+ modules)
├── components/             # Shared components
│   ├── Sidebar.tsx         # Navigation sidebar with RBAC
│   ├── RoleGuard.tsx       # Route protection component
│   ├── RoleSwitcher.tsx    # Dev role switching
│   └── ...
├── hooks/                  # Custom React hooks
│   ├── useWebSocket.ts     # WebSocket lifecycle
│   ├── useTheme.ts         # Dark mode + styling
│   ├── useTEWSCalculator.ts # TEWS computation
│   ├── useDataInit.ts      # Store hydration
│   ├── useMyShift.ts       # Current shift info
│   ├── useDynamicRetriage.ts # AI re-triage
│   └── useVitalSimulator.ts # Dev vital simulation
├── modules/                # Feature modules
│   ├── dashboard/          # Role-specific dashboards
│   ├── entry/              # Patient registration
│   ├── triage/             # Triage queue + forms
│   ├── patients/           # Patient list
│   ├── patient/            # Patient detail
│   ├── monitoring/         # ED monitoring dashboard
│   ├── vitals/             # Vital monitoring
│   ├── alerts/             # Alert management
│   ├── doctor/             # Doctor workspace
│   ├── iot/                # IoT device management
│   ├── sepsis/             # Sepsis screening
│   ├── fasttrack/          # Fast-track protocols
│   ├── hypoglycemia/       # Hypoglycemia management
│   ├── isolation/          # Infection isolation
│   ├── lab/                # Lab orders
│   ├── pathway/            # Clinical pathways
│   ├── medsafety/          # Medication safety
│   ├── icu/                # ICU escalation
│   ├── referral/           # Referral management
│   ├── documentation/      # Clinical documentation
│   ├── handover/           # Handover reports
│   ├── safety/             # Safety incidents
│   ├── admin/              # Hospital + user management
│   ├── shift/              # Shift assignment
│   ├── audit/              # Audit trail
│   ├── reports/            # Reports & analytics
│   ├── quality/            # Quality dashboard
│   ├── prediction/         # Surge prediction
│   ├── mohreport/          # MOH reports
│   ├── governance/         # Governance admin
│   ├── settings/           # System settings
│   ├── notifications/      # Notifications
│   └── profile/            # User profile
├── store/                  # Zustand state stores
│   ├── authStore.ts        # Authentication state
│   ├── patientStore.ts     # Patient data
│   ├── vitalStore.ts       # Vital signs
│   ├── alertStore.ts       # Clinical alerts
│   ├── deviceStore.ts      # IoT devices
│   ├── auditStore.ts       # Audit log
│   ├── tewsHistoryStore.ts # TEWS trend history
│   └── themeStore.ts       # Theme preferences
├── types/                  # TypeScript types
│   ├── index.ts            # Core domain types
│   └── roles.ts            # RBAC types & permissions
├── utils/                  # Utility functions
│   ├── tewsCalculator.ts   # TEWS scoring logic
│   ├── vitalValidation.ts  # Physiologic validation
│   └── iotDeviceManager.ts # IoT utilities
├── pages/                  # Full-page components
│   ├── LandingPage.tsx     # Public landing
│   └── LoginPage.tsx       # Login page
└── App.tsx                 # Root router + layout
```

### 7.2 State Management (Zustand Stores)

| Store | Purpose | Key State | API Integration |
|-------|---------|-----------|-----------------|
| authStore | Authentication | user, isLoading, error | POST /auth/login, /auth/refresh |
| patientStore | Patient data | patients[], isLoading | POST /patients/register, GET /visits/active |
| vitalStore | Vital signs | vitalsByPatient, vitalHistory | GET /vitals/visit/{id} |
| alertStore | Clinical alerts | alerts[], isLoading | GET/PATCH /alerts/* |
| deviceStore | IoT devices | devices Map | GET /iot/devices/hospital/{id} |
| auditStore | Audit trail | entries[] | Local (no backend yet) |
| tewsHistoryStore | TEWS trends | historyByPatient Map | Local computation |
| themeStore | Dark mode | isDark | localStorage |

### 7.3 Route Protection

All routes are protected by the `RoleGuard` component which checks `canAccessPage(user.role, page)`:

```tsx
<Route path="/triage" element={
  <RoleGuard page="triage">
    <TriageQueue />
  </RoleGuard>
} />
```

If the user's role doesn't have access, they see an "Access Denied" message.

### 7.4 Real-Time Data Flow

```
ESP32 Device → POST /api/v1/iot/stream/ingest (API key)
    → VitalStreamService validates + stores
    → AlertGenerationService checks thresholds
    → DynamicRetriageService analyzes trends
    → WebSocket broadcast to subscribed clients
        → /topic/hospital/{id}/alerts
        → /topic/zone/{id}/{zone}
        → /topic/user/{id}/alerts
        → /topic/visit/{id}/vitals
    → Frontend Zustand stores update
    → React components re-render
```

---

## 8. System Design Support

This section provides the detail needed to generate system design diagrams.

### 8.1 Use Case Diagram Actors & Use Cases

**Actors:**
1. Super Admin (National level)
2. Hospital Admin
3. Doctor
4. Nurse
5. Triage Nurse
6. Registrar
7. Paramedic
8. Lab Technician
9. Read-Only User
10. IoT Device (system actor)
11. AI Alert Engine (system actor)

**Use Cases by Actor:**

| Actor | Use Cases |
|-------|-----------|
| Super Admin | Create hospital, Manage hospitals, Create hospital admin, View national reports, Manage governance policies, View quality metrics, View MOH reports, View audit trail, Manage surge predictions |
| Hospital Admin | Create staff accounts, Manage staff, View hospital reports, View audit trail, Monitor ED, View quality metrics, Manage safety incidents, Configure settings |
| Doctor | View patient list, Review triage, Override triage category, Record disposition, Order investigations, Order medications, Write clinical notes, Write diagnoses, Order lab tests, View monitoring, Acknowledge alerts, Create referrals, Create clinical documents, Activate clinical pathways, Request ICU escalation |
| Nurse | Register patient, Start triage, Record vitals, Administer medication, Countersign medications, Write nursing notes, View monitoring, Acknowledge alerts, Screen for sepsis, Screen for infection, Report safety incidents |
| Triage Nurse | Register patient, Perform triage (adult/pediatric), Record vitals, View queue, Write triage notes, Handover patients |
| Registrar | Register patient, Search patients, Create referrals |
| Paramedic | Register patient, Record vitals, Create handover report, View patient, Create referrals |
| Lab Technician | View lab orders, Process specimens, Enter results, Flag critical values |
| IoT Device | Send vital data, Send heartbeat, Report battery/signal |
| AI Alert Engine | Generate threshold alerts, Detect deterioration, Trigger re-triage, Escalate to zone doctor, Enforce SATS timing |

### 8.2 Class Diagram Entities

**Core Domain Model:**

```
Hospital (1) ─────< User (N)
Hospital (1) ─────< Patient (N)
Hospital (1) ─────< IoTDevice (N)

Patient (1) ──────< Visit (N)

Visit (1) ────────< VitalSigns (N)
Visit (1) ────────< TriageRecord (N)
Visit (1) ────────< ClinicalAlert (N)
Visit (1) ────────< MedicationAdministration (N)
Visit (1) ────────< Diagnosis (N)
Visit (1) ────────< Investigation (N)
Visit (1) ────────< ClinicalNote (N)
Visit (1) ────────< DeviceSession (N)
Visit (1) ────────< VitalStream (N)
Visit (1) ────────< SepsisScreening (N)
Visit (1) ────────< FastTrackActivation (N)
Visit (1) ────────< HypoglycemiaEvent (N)
Visit (1) ────────< InfectionScreening (N)
Visit (1) ────────< ClinicalDocument (N)
Visit (1) ────────< MedicationSafetyCheck (N)
Visit (1) ────────< LabOrder (N)
Visit (1) ────────< PathwayActivation (N)
Visit (1) ────────< IcuEscalation (N)
Visit (1) ────────< Referral (N)
Visit (1) ────────< HandoverReport (N)

IoTDevice (1) ───< DeviceSession (N)
DeviceSession (1) < VitalStream (N)

ClinicalPathway (1) ──< PathwayStep (N)
ClinicalPathway (1) ──< PathwayActivation (N)
PathwayActivation (1) < PathwayStepCompletion (N)
PathwayStep (1) ──────< PathwayStepCompletion (N)

ClinicalDocument ──> ClinicalDocument [amendment chain]
TriageRecord ──> User [triaged_by]
ClinicalAlert ──> User [acknowledged_by, target_doctor]
ShiftAssignment ──> User, Hospital, EdZone
```

### 8.3 Architecture Diagram Components

**Layer Architecture:**

```
┌─────────────────────────────────────────────────┐
│                   CLIENT LAYER                   │
│  React 18 + TypeScript + Zustand + TailwindCSS  │
│  ┌──────────┐ ┌──────────┐ ┌──────────────────┐ │
│  │ Modules  │ │  Hooks   │ │  Zustand Stores  │ │
│  └────┬─────┘ └────┬─────┘ └────────┬─────────┘ │
│       └─────────────┴───────────────┘            │
│              ┌──────┴──────┐                     │
│              │ API Client  │                     │
│              │ + WebSocket │                     │
│              └──────┬──────┘                     │
└─────────────────────┼───────────────────────────┘
                      │ HTTPS / WSS
┌─────────────────────┼───────────────────────────┐
│                GATEWAY / PROXY                   │
│            Vite Dev Proxy / Nginx                │
└─────────────────────┼───────────────────────────┘
                      │
┌─────────────────────┼───────────────────────────┐
│               API LAYER (Spring Boot)            │
│  ┌──────────────┐  ┌──────────────┐             │
│  │ JWT Auth     │  │ IoT API Key  │             │
│  │ Filter       │  │ Filter       │             │
│  └──────┬───────┘  └──────┬───────┘             │
│         └──────────┬───────┘                     │
│         ┌──────────┴──────────┐                  │
│         │   REST Controllers   │                 │
│         │   (30+ controllers)  │                 │
│         └──────────┬──────────┘                  │
│         ┌──────────┴──────────┐                  │
│         │   Service Layer      │                 │
│         │   + Business Logic   │                 │
│         └──────────┬──────────┘                  │
│         ┌──────────┴──────────┐                  │
│         │   JPA Repositories   │                 │
│         └──────────┬──────────┘                  │
│         ┌──────────┴──────────┐                  │
│         │  WebSocket (STOMP)   │                 │
│         │  Broadcast Service   │                 │
│         └─────────────────────┘                  │
└─────────────────────┼───────────────────────────┘
                      │
┌─────────────────────┼───────────────────────────┐
│              DATA LAYER                          │
│  ┌──────────────────┴────────────────────────┐  │
│  │         PostgreSQL 14+                     │  │
│  │         33+ Tables                         │  │
│  │         Flyway Migrations (V1-V14)         │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│              IoT DEVICE LAYER                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐        │
│  │  ESP32   │ │  ESP32   │ │  ESP32   │  ...   │
│  │ Monitor  │ │ Monitor  │ │ Monitor  │        │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘        │
│       └─────────────┴────────────┘               │
│         HTTP POST every 5 seconds                │
│         + Heartbeat every 15 seconds             │
└─────────────────────────────────────────────────┘
```

### 8.4 Sequence Diagram Key Flows

See Section 9 for detailed sequence diagrams.

### 8.5 Activity Diagram Key Processes

See Section 9 for detailed activity diagrams.

### 8.6 ER Diagram

The full ER diagram can be generated from Section 4.2 (Entity Relationship Summary) and Section 4.3 (Table Definitions). Key relationships:

- **hospitals** is the tenant root (all data is hospital-scoped)
- **visits** is the central clinical entity (all clinical data links through visits)
- **patients** own visits (1-to-many)
- **users** are referenced by triage_records, clinical_alerts, medication_administrations, shift_assignments
- **iot_devices** link to visits through device_sessions
- **clinical_pathways** have a 3-level hierarchy: pathway -> steps -> activations -> step_completions

---

## 9. Module-Specific Workflows

### 9.1 Patient Registration Workflow

**Activity Diagram:**

```
[Start] → Registrar/Nurse opens Entry Registration
    → Step 1: Arrival Information
        - Select arrival mode (Walk-in, Ambulance, Referral)
        - Record arrival time
        - Select referring facility (if referral)
    → Step 2: Patient Demographics
        - Enter first name, last name
        - Date of birth → auto-calculate age
        - Gender, National ID
        - If pediatric (age < 13): show Guardian section
        - If adult: show phone, emergency contact
    → Step 3: Chief Complaint
        - Multi-select from common complaints list
        - Optional "Other" free text
    → Step 4: Initial Observations
        - Mobility (Walking, With Help, Stretcher)
        - Assign triage nurse (filtered to TRIAGE_NURSE role only)
    → Step 5: Review & Submit
        - Review all entered data
        - Submit → POST /api/v1/patients/register
            → Creates Patient + Visit atomically
            → Visit status = REGISTERED
    → [Success] → Patient appears in Triage Queue
    → [Error] → Show error, allow retry
```

**Sequence Diagram:**

```
Registrar → EntryRegistration: Fill 5-step form
EntryRegistration → patientStore.registerPatientApi(): Submit data
patientStore → API Client: POST /api/v1/patients/register
API Client → Backend: HTTP POST with JWT
Backend → PatientService: createPatientWithVisit()
PatientService → PatientRepository: save(patient)
PatientService → VisitService: createVisit(patient, hospital)
VisitService → VisitRepository: save(visit)
Backend → API Client: 201 Created (PatientResponse + VisitResponse)
API Client → patientStore: Update patients array
patientStore → EntryRegistration: Success notification
EntryRegistration → Registrar: "Patient registered successfully"
```

### 9.2 Triage Assessment Workflow

**Activity Diagram:**

```
[Start] → Triage Nurse selects patient from queue
    → Choose form type: Adult or Pediatric (age < 13)

    Adult Triage:
    → Step 1: Emergency Signs Check
        - Airway compromise? Breathing distress? Cardiac arrest?
        - Convulsions? Coma? Hypoglycemia? Purpuric rash?
        - Burns to face/inhalation? Uncontrolled hemorrhage?
        - Penetrating wound neck/chest?
        → If ANY emergency sign = YES → Category = RED (Emergency)

    → Step 2: Record Vitals
        - Heart rate, Respiratory rate, SpO2
        - Systolic BP, Diastolic BP, Temperature
        - Blood glucose, Pain score
        - AVPU score, Mobility, Trauma status
        → Calculate TEWS score automatically

    → Step 3: Very Urgent Signs (if not RED)
        - Medical discriminators (10+ checks)
        - Trauma discriminators (8+ checks)
        → If ANY very urgent sign = YES → Category = min ORANGE

    → Step 4: Urgent Signs (if not RED/ORANGE)
        - 13+ urgent discriminators
        → If ANY urgent sign = YES → Category = min YELLOW

    → Step 5: Special Considerations
        - Acute trauma, Seizure history, Assault/abuse, Suicide attempt

    → Compute final category:
        - MAX(emergency_override, discriminator_minimum, TEWS_category)

    → Step 6: Clinical Notes & Form Footer
        - Clinical notes, Presenting complaints
        - Triage nurse name
        - Notified doctor name + time

    → Submit → POST /api/v1/triage
        → Visit status updated to TRIAGED
        → Alerts generated if RED/ORANGE
        → WebSocket notification to zone

    Pediatric Triage (Child 3-12):
    → Same flow but with child-specific emergency signs:
        - Central cyanosis, Pulse absent/low
        - Cold peripheries + lethargy/weak pulse
        - Severe dehydration (skin pinch, lethargy, sunken eyes)
        - Record weight (kg) and height (cm)
```

**Sequence Diagram:**

```
TriageNurse → TriageQueue: Select patient
TriageQueue → PediatricTriageForm/AdultTriageForm: Navigate
TriageNurse → TriageForm: Complete all sections
TriageForm → useTEWSCalculator: Compute score + category
useTEWSCalculator → tewsHistoryStore: Record TEWS entry
TriageNurse → TriageForm: Submit
TriageForm → triageApi.perform(): POST /api/v1/triage
Backend → TriageService: performTriage()
TriageService → TEWSCalculator: Calculate TEWS server-side
TriageService → TriageRecordRepository: save(triageRecord)
TriageService → VisitService: updateTriageCategory()
TriageService → AlertGenerationService: checkAndGenerateAlerts()
AlertGenerationService → ClinicalAlertRepository: save(alerts)
AlertGenerationService → WebSocketBroadcast: /topic/hospital/{id}/alerts
WebSocketBroadcast → Frontend alertStore: New alert received
Frontend → AlertsView: Re-render with new alert
```

### 9.3 IoT Vital Monitoring Workflow

**Activity Diagram:**

```
[Device Registration]
    Hospital Admin → Register IoT device
        - Enter serial number, name, type
        - System generates unique API key
        - Device status = REGISTERED

    Hospital Admin → Power On device
        - Device status = ONLINE
        - Device begins sending heartbeats

[Monitoring Session]
    Nurse → Start Monitoring Session
        - Select available (ONLINE) device
        - Assign to patient's active visit
        - Device status = MONITORING
        - Session created (session_active = true)

    [Loop every 5 seconds]
        ESP32 → POST /api/v1/iot/stream/ingest
            - Header: X-Device-API-Key
            - Body: HR, SpO2, RR, Temp, BP, Glucose, ECG
        Backend → VitalStreamService: validate + store
            → Check signal quality
            → Validate physiologic ranges
            → If invalid → reject + increment rejected_readings
            → If valid → store to vital_streams
                → Update device.last_data_at
                → Increment session.total_readings
                → Periodic snapshot → vital_signs table
                → WebSocket → /topic/visit/{id}/vitals
        Backend → AlertGenerationService
            → Check thresholds
            → If breached → generate alert
            → WebSocket → /topic/hospital/{id}/alerts
        Backend → DynamicRetriageService
            → Analyze 30-second trends
            → If deterioration → trigger re-triage
            → If improvement → suggest de-escalation

    [Loop every 15 seconds]
        ESP32 → POST /api/v1/iot/stream/heartbeat
            - Header: X-Device-API-Key
        Backend → Update device.last_heartbeat_at
            → If no heartbeat for 30s → device OFFLINE alert

    Nurse → Stop Monitoring Session
        - Session ended (session_active = false)
        - Record end reason
        - Device status = ONLINE (available again)
```

### 9.4 Sepsis Screening Workflow

**Activity Diagram:**

```
[Start] → Clinician opens Sepsis Screening for visit
    → Step 1: qSOFA Assessment
        - Altered mentation (GCS < 15)? → +1
        - Respiratory rate >= 22? → +1
        - Systolic BP <= 100? → +1
        → qSOFA score = sum (0-3)

    → Step 2: SIRS Criteria
        - Temperature > 38.3 or < 36? → +1
        - Heart rate > 90? → +1
        - Respiratory rate > 20? → +1
        - WBC > 12k or < 4k? → +1
        → SIRS score = sum (0-4)

    → Step 3: Suspected Infection Source
        - Document suspected source
        - Measure lactate level

    → Compute Status:
        - qSOFA < 2, SIRS < 2 → NO_SEPSIS
        - SIRS >= 2 → SIRS_POSITIVE
        - qSOFA >= 2 → SEPSIS_SUSPECTED
        - Lactate > 2 + organ dysfunction → SEVERE_SEPSIS
        - Vasopressors needed + Lactate > 2 → SEPTIC_SHOCK

    → If SEPSIS_SUSPECTED or worse:
        → Start 1-Hour Bundle
            □ Blood cultures obtained
            □ Broad-spectrum antibiotics administered
            □ IV crystalloid 30ml/kg bolus started
            □ Lactate measured
            □ Vasopressors if MAP < 65 mmHg
            □ Repeat lactate if initial > 2 mmol/L
        → Track bundle completion time
        → Generate SEPSIS_SCREENING alert

    → Submit → POST /api/v1/sepsis/screen/{visitId}
```

### 9.5 Lab Order Workflow

**Activity Diagram:**

```
[Start] → Doctor orders lab test
    → Select test (name, code)
    → Set priority: STAT (30min), URGENT (2hr), ROUTINE (24hr)
    → Set specimen type
    → Submit → POST /api/v1/lab/order
        → Lab order created (status: ORDERED)
        → Order number generated automatically

[Specimen Collection]
    Nurse → Mark specimen collected
        → PUT /api/v1/lab/{id}/collect-specimen
        → Status: SPECIMEN_COLLECTED

[Lab Processing]
    Lab Tech → Mark received by lab
        → PUT /api/v1/lab/{id}/receive
    Lab Tech → Process specimen
    Lab Tech → Enter result
        → PUT /api/v1/lab/{id}/result
        → Auto-check reference ranges
        → Flag abnormal/critical values
        → If CRITICAL:
            → Generate CRITICAL_LAB_RESULT alert
            → WebSocket notification to ordering doctor
            → Track time until acknowledged
            → If STAT and overdue → STAT_LAB_OVERDUE alert

[Critical Value Acknowledgement]
    Doctor → Acknowledge critical value
        → PUT /api/v1/lab/{id}/acknowledge-critical
        → Document acknowledgement time

[Turnaround Time Tracking]
    System → Calculate TAT (ordered_at → resulted_at)
    System → Compare to priority target (30/120/1440 min)
    System → Generate overdue alerts if exceeded
```

### 9.6 Referral Workflow

**Activity Diagram:**

```
[Start] → Clinician initiates referral
    → Select referral type (Upward/Lateral/Downward/Emergency)
    → Enter receiving hospital details
    → Document referral reason + clinical summary
    → Submit → POST /api/v1/referrals/initiate
        → Status: INITIATED
        → Alert: REFERRAL_INITIATED

[Contact Phase]
    Clinician → Contact receiving facility
        → PUT /api/v1/referrals/{id}/contact
        → Status: RECEIVING_FACILITY_CONTACTED

    Receiving → Accept or Decline
        → If ACCEPTED → Status: ACCEPTED
        → If DECLINED → Status: DECLINED → Consider alternative

[Stabilization Phase]
    Clinician → Complete pre-transfer checklist:
        □ Airway secured
        □ Breathing stable
        □ Circulation stable
        □ IV access established
        □ Medications documented
        □ Allergies documented
        □ Blood type documented
        □ Consent obtained
        □ Referral form completed
        □ Patient ID band applied
    → PUT /api/v1/referrals/{id}/stabilize
    → If checklist incomplete → REFERRAL_STABILIZATION_INCOMPLETE alert

[Transport Phase]
    → Select transport mode (SAMU, Hospital Ambulance, etc.)
    → Assign escort if required
    → Record departure → PUT /api/v1/referrals/{id}/depart
        → Status: IN_TRANSIT
    → Record arrival → PUT /api/v1/referrals/{id}/arrive
        → Status: RECEIVED_AT_DESTINATION
    → Complete referral → PUT /api/v1/referrals/{id}/complete
        → Status: COMPLETED
        → Calculate actual transfer time
```

### 9.7 User Management Workflow

**Activity Diagram:**

```
[Super Admin creates Hospital]
    → POST /api/v1/hospitals
    → Enter: name, code, address, tier, capacities
    → Hospital created (is_active = true)

[Super Admin creates Hospital Admin]
    → POST /api/v1/users
    → Enter: name, email, password, role=HOSPITAL_ADMIN
    → Assign to hospital
    → Hospital Admin can now log in

[Hospital Admin creates Staff]
    → POST /api/v1/users
    → Enter: name, email, password
    → Select role: DOCTOR, NURSE, TRIAGE_NURSE, REGISTRAR, etc.
    → Select designation (filtered by role)
    → Enter department, employee number, license
    → User created → can log in
    → RBAC applied automatically based on role

[Hospital Admin manages Staff]
    → View staff list (GET /api/v1/users/hospital/{id})
    → Update user details (PUT /api/v1/users/{id})
    → Change designation (PATCH /api/v1/users/{id}/designation)
    → Deactivate user (DELETE /api/v1/users/{id})
        → Soft delete (is_active = false)
        → User can no longer log in
```

### 9.8 Alert Escalation Workflow

**Activity Diagram:**

```
[Trigger] → Vital threshold breached / Deterioration detected
    → AlertGenerationService creates alert
    → Determine target zone (based on visit location)
    → Set escalation tier = 1

[Tier 1: Zone Notification]
    → WebSocket → /topic/zone/{hospitalId}/{zone}
    → All nurses/doctors in zone receive alert
    → Wait for acknowledgement (SATS target time)

    → If acknowledged → Alert resolved
    → If NOT acknowledged within target time:
        → Escalation tier = 2

[Tier 2: Doctor Notification]
    → Find assigned doctor for zone (from shift_assignments)
    → WebSocket → /topic/user/{doctorId}/alerts
    → DOCTOR_NOTIFICATION alert generated
    → Wait for acknowledgement

    → If acknowledged → Alert resolved
    → If NOT acknowledged:
        → Escalation tier = 3

[Tier 3: ED Head Notification]
    → DOCTOR_ESCALATION alert generated
    → Notify ED Head / supervising doctor
    → WAITING_TIME_EXCEEDED alert if SATS time breached

[SATS Target Times]
    RED: 0 minutes (immediate)
    ORANGE: 10 minutes
    YELLOW: 30 minutes
    GREEN: 60 minutes
```

### 9.9 Shift Assignment Workflow

**Activity Diagram:**

```
[Start] → Hospital Admin / Charge Nurse opens Shift Assignment
    → Select date and shift period:
        - DAY (07:00 - 19:00)
        - NIGHT (19:00 - 07:00)

    → For each ED Zone (RESUS, ACUTE, GENERAL, TRIAGE, OBSERVATION, ISOLATION, PEDIATRIC):
        → Assign staff member from hospital users
        → Select shift function:
            - CHARGE_NURSE
            - TRIAGE_NURSE
            - ZONE_NURSE
            - PRIMARY_DOCTOR
            - SUPERVISING_DOCTOR
            - RESIDENT
        → POST /api/v1/shifts/hospital/{id}/assign

    → Staff members can view their assignment via useMyShift() hook
    → Zone assignment determines:
        - Which zone alerts they receive via WebSocket
        - Which patients they see in monitoring view
        - Alert escalation targeting

    → End shift: PATCH /api/v1/shifts/{id}/end
```

### 9.10 Clinical Documentation Workflow

**Activity Diagram:**

```
[Start] → Clinician opens Clinical Documentation
    → Select document type:
        - Initial Assessment
        - Progress Note
        - Procedure Note
        - Consultation Note
        - Discharge Summary
        - Transfer Summary
        - Nursing Assessment
        - Triage Narrative

    → Select template (optional)
    → Write content (structured sections)
    → Attach current vital signs (optional)

    → Save draft → POST /api/v1/documents
        → is_signed = false

    → Sign document:
        → Author signs (signed_at recorded)
        → is_signed = true
        → Records author_role + license_number

    → Co-sign (if required):
        → Senior clinician reviews
        → co_signed_by_name + co_signed_at recorded

    → Amendment (if needed):
        → Create new document with:
            - is_amendment = true
            - original_document_id = parent document
            - amendment_reason (required)
        → Original document remains unchanged (audit trail)
```

---

## 10. API Reference

### 10.1 Base URL

```
Production: https://{hostname}/api/v1
Development: http://localhost:8080/api/v1
```

### 10.2 Authentication

All endpoints (except public) require JWT Bearer token:
```
Authorization: Bearer <access_token>
```

IoT device endpoints use API key:
```
X-Device-API-Key: <device_api_key>
```

### 10.3 Response Format

All responses are wrapped in:
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },
  "timestamp": "2026-04-15T10:30:00Z"
}
```

### 10.4 Endpoint Summary

| Module | Base Path | Endpoints | Auth |
|--------|-----------|-----------|------|
| Auth | /auth | login, refresh | Public |
| Hospitals | /hospitals | CRUD + list | JWT (SUPER_ADMIN) |
| Users | /users | CRUD + list + designation | JWT (SUPER_ADMIN, HOSPITAL_ADMIN) |
| Patients | /patients | create, register, get, search | JWT (clinical roles) |
| Visits | /visits | create, get, update status, disposition, zone query | JWT |
| Vitals | /vitals | record, get latest, get history | JWT (clinical roles) |
| Triage | /triage | perform, get history, get latest | JWT (clinical roles) |
| Alerts | /alerts | get, acknowledge, zone/doctor queries | JWT |
| Clinical Notes | /clinical-notes | CRUD + type queries | JWT (clinical roles) |
| Diagnoses | /diagnoses | CRUD + type queries | JWT (DOCTOR, TRIAGE_NURSE) |
| Investigations | /investigations | order, collect, result, cancel | JWT |
| Medications | /medications | prescribe, administer, countersign, hold, cancel | JWT |
| IoT Devices | /iot | register, power on/off, list | JWT (SUPER_ADMIN, HOSPITAL_ADMIN) |
| IoT Monitoring | /iot/monitoring | start/stop session, get active | JWT (clinical roles) |
| IoT Stream | /iot/stream | ingest, heartbeat | API Key |
| Shifts | /shifts | assign, get current, update, end | JWT |
| Sepsis | /sepsis | screen, get history, start bundle | JWT |
| Fast-Track | /fast-track | activate, update status, record ECG/CT | JWT |
| Hypoglycemia | /hypoglycemia | detect, treat, resolve | JWT |
| Isolation | /isolation | screen, assign room, end, notify health | JWT |
| Lab | /lab | order, collect, receive, result, critical values | JWT |
| Medication Safety | /med-safety | check, override | JWT |
| Clinical Pathways | /pathways | list, activate, complete steps | JWT |
| ICU Escalation | /icu | request, respond, transfer | JWT |
| Referrals | /referrals | initiate, contact, accept, stabilize, depart, arrive, complete | JWT |
| Documents | /documents | create, sign, co-sign, amend | JWT |
| Handover | /handover | create, acknowledge | JWT |
| Safety Incidents | /safety-incidents | report, investigate, close | JWT |
| Quality | /quality | get metrics, snapshots | JWT |
| Prediction | /prediction | get surge risk, patient outcomes | JWT |
| MOH Reports | /moh-reports | generate, submit | JWT |
| Governance | /governance | manage policies, audit | JWT |

---

## 11. Real-Time Communication

### 11.1 WebSocket Configuration

- **Endpoint:** `/ws` (SockJS fallback)
- **Protocol:** STOMP over WebSocket
- **Broker:** Simple in-memory broker
- **Destination Prefix:** `/topic`

### 11.2 Subscription Topics

| Topic | Description | Subscribers |
|-------|-------------|-------------|
| `/topic/hospital/{hospitalId}/alerts` | Hospital-wide clinical alerts | All staff in hospital |
| `/topic/user/{userId}/alerts` | User-targeted notifications (doctor escalation) | Specific doctor |
| `/topic/zone/{hospitalId}/{zone}` | Zone-scoped alerts and updates | Staff assigned to zone |
| `/topic/visit/{visitId}/vitals` | Real-time vital stream for specific patient | Monitoring views |

### 11.3 Message Flow

```
IoT Device → REST API (/iot/stream/ingest)
    → Backend processes + validates
    → Backend generates alerts if needed
    → SimpMessagingTemplate.convertAndSend()
        → STOMP broker distributes to subscribers
        → Frontend STOMP client receives
        → Zustand store updated
        → React re-renders
```

---

## 12. Security Architecture

### 12.1 Authentication Flow

```
1. User submits credentials → POST /api/v1/auth/login
2. Backend validates → BCrypt password check
3. If valid → Generate JWT access token (15 min) + refresh token (24 hr)
4. Frontend stores:
   - Access token: in-memory only
   - Refresh token: localStorage
   - User profile: localStorage
5. All API requests include: Authorization: Bearer <access_token>
6. On 401 → Auto-refresh using refresh token → Retry request
7. If refresh fails → Redirect to login
```

### 12.2 Authorization Layers

1. **Route-level (Frontend):** `RoleGuard` component checks `canAccessPage(role, page)`
2. **Sidebar Filtering:** Items filtered by `ROLE_PAGES[user.role]`
3. **API-level (Backend):** `@PreAuthorize("hasAnyRole(...)")` on controller methods
4. **Feature-level:** `hasFeature(role, feature)` for granular UI controls

### 12.3 IoT Device Security

- Each device has a unique, randomly generated API key
- API key passed via `X-Device-API-Key` header
- IoT endpoints are separate from JWT-protected endpoints
- Device data is validated for physiologic ranges before storage
- Rejected data is counted and flagged

### 12.4 Data Security

- All passwords hashed with BCrypt
- JWT secret configurable via environment variable
- CSRF disabled (stateless API)
- CORS configured for frontend origin
- All data soft-deleted (never hard-deleted)
- Optimistic locking prevents concurrent modification
- Audit columns on all tables (created_by, last_modified_by)

---

## 13. Deployment & Configuration

### 13.1 Backend Configuration

**application.properties:**
```properties
server.port=8080
spring.datasource.url=jdbc:postgresql://localhost:5432/smarttriage_dev
spring.datasource.username=postgres
spring.datasource.password=password
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true

# JWT
smarttriage.security.jwt.access-token-expiration-ms=900000      # 15 min
smarttriage.security.jwt.refresh-token-expiration-ms=86400000   # 24 hr

# IoT
smarttriage.iot.heartbeat-check-interval-ms=15000               # 15 sec
smarttriage.iot.default-data-interval-seconds=5                  # 5 sec
smarttriage.iot.default-heartbeat-timeout-seconds=30             # 30 sec
```

### 13.2 Frontend Configuration

**vite.config.ts:**
- Path alias: `@` → `./src`
- API proxy: `/api` → `http://localhost:8080`
- WebSocket proxy: `/ws` → `ws://localhost:8080`

### 13.3 Development Setup

```bash
# Backend
cd SmartTriage-server
./gradlew bootRun

# Frontend
cd SmartTriage_Frontend_V6
npm install
npm run dev

# Database
# PostgreSQL running on localhost:5432
# Database: smarttriage_dev
# Flyway auto-migrates on startup
```

### 13.4 Production Build

```bash
# Frontend
npm run build    # Outputs to dist/

# Backend
./gradlew build  # Outputs to build/libs/
java -jar build/libs/SmartTriage-server.jar
```

---

## Appendix A: SATS/TEWS Quick Reference

### SATS Decision Flow

```
Step 1: Is the patient alive?
    NO → BLUE (Dead)
    YES → Continue

Step 2: Any Emergency Signs?
    YES → RED (Emergency, immediate)
    NO → Continue

Step 3: Calculate TEWS
    Score ≥ 7 → RED (Emergency)
    Score 5-6 → ORANGE (Very Urgent)
    Score 3-4 → YELLOW (Urgent)
    Score 0-2 → GREEN (Standard)

Step 4: Check Discriminators (override minimum)
    Very Urgent discriminator → minimum ORANGE
    Urgent discriminator → minimum YELLOW

Step 5: Final Category = MAX(TEWS category, discriminator minimum)
```

### TEWS Score Table

| Component | 0 | 1 | 2 | 3 |
|-----------|---|---|---|---|
| Mobility | Walking | With help | Stretcher | - |
| RR | 9-14 | 15-20 | 21-29 | ≥30 or ≤8 |
| HR | 51-100 | 101-110 or 41-50 | 111-129 or <41 | ≥130 |
| SBP | 100-199 | 80-99 | 70-79 or ≥200 | <70 |
| Temp | 35.0-38.4 | 34.0-34.9 | 33.0-33.9 or ≥38.5 | <33 or ≥41 |
| AVPU | Alert | Confused/Verbal | Pain | Unresponsive |
| Trauma | No | Yes | - | - |

**Maximum TEWS Score: 17**

---

## Appendix B: Rwanda-Specific Features

| Feature | Rwanda Context |
|---------|---------------|
| Notifiable Diseases | TB, Cholera, Measles, Ebola, Marburg, Malaria (severe) - all require MOH notification |
| REML Formulary | Rwanda Essential Medicines List integrated into drug safety checks |
| SAMU Integration | National ambulance service (Service d'Aide Medicale Urgente) referral numbers |
| RHMIS | Rwanda Health Management Information System case tracking |
| Hospital Tiers | District, Regional, Tertiary (Teaching) hospitals |
| MOH Reporting | Daily, weekly, monthly, quarterly, annual reports to Ministry of Health |
| Multi-language | English primary (French/Kinyarwanda planned) |

---

*End of Documentation*
