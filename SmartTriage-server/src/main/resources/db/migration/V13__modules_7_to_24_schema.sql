-- =====================================================================
-- V13: Schema for Modules 7–24
-- SmartTriage — Rwanda Hospital Emergency Department System
-- =====================================================================
-- Module 7:  Dynamic Re-triage AI Engine (uses existing tables)
-- Module 8:  Sepsis Detection Engine
-- Module 9:  Stroke / MI Fast-Track Module
-- Module 10: Hypoglycemia Enforcement Module
-- Module 11: Infection Isolation & Public Health Module
-- Module 12: Clinical Documentation System
-- Module 13: Medication Safety Engine
-- Module 14: Laboratory Integration Module
-- Module 15: Clinical Pathway Automation Engine
-- Module 16: ICU Escalation Logic
-- Module 17: Referral & Inter-Hospital Transfer Module
-- Module 18: System Resilience & Offline Continuity Engine
-- Module 19: Patient Safety Incident Reporting System
-- Module 20: Report & Handover Module
-- Module 21: Quality Metrics & Dashboard Engine
-- Module 22: AI-Assisted Risk Prediction Engine
-- Module 23: National Health Data Reporting Interface
-- Module 24: Clinical Governance & Policy Control Engine
-- =====================================================================

-- =====================================================================
-- MODULE 8: Sepsis Detection Engine
-- =====================================================================
CREATE TABLE sepsis_screenings (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                    UUID NOT NULL REFERENCES visits(id),
    screened_at                 TIMESTAMP NOT NULL,
    screened_by_name            VARCHAR(255),
    sepsis_status               VARCHAR(20) NOT NULL,

    -- qSOFA criteria
    qsofa_score                 INTEGER NOT NULL DEFAULT 0,
    altered_mentation           BOOLEAN NOT NULL DEFAULT FALSE,
    respiratory_rate_high       BOOLEAN NOT NULL DEFAULT FALSE,
    systolic_bp_low             BOOLEAN NOT NULL DEFAULT FALSE,

    -- SIRS criteria
    sirs_score                  INTEGER NOT NULL DEFAULT 0,
    temperature_criteria_met    BOOLEAN NOT NULL DEFAULT FALSE,
    heart_rate_criteria_met     BOOLEAN NOT NULL DEFAULT FALSE,
    respiratory_rate_criteria_met BOOLEAN NOT NULL DEFAULT FALSE,
    wbc_criteria_met            BOOLEAN NOT NULL DEFAULT FALSE,

    suspected_infection_source  TEXT,
    lactate_level               DOUBLE PRECISION,

    -- 1-hour Sepsis Bundle (Rwanda MoH)
    bundle_started_at           TIMESTAMP,
    bundle_completed_at         TIMESTAMP,
    blood_culture_obtained      BOOLEAN NOT NULL DEFAULT FALSE,
    broad_spectrum_antibiotics  BOOLEAN NOT NULL DEFAULT FALSE,
    iv_crystalloid_bolus        BOOLEAN NOT NULL DEFAULT FALSE,
    lactate_measured            BOOLEAN NOT NULL DEFAULT FALSE,
    vasopressors_if_needed      BOOLEAN NOT NULL DEFAULT FALSE,
    repeat_lactate_if_elevated  BOOLEAN NOT NULL DEFAULT FALSE,

    notes                       TEXT
);

CREATE INDEX idx_sepsis_visit ON sepsis_screenings(visit_id);
CREATE INDEX idx_sepsis_status ON sepsis_screenings(sepsis_status);
CREATE INDEX idx_sepsis_screened_at ON sepsis_screenings(screened_at);
CREATE INDEX idx_sepsis_active ON sepsis_screenings(is_active);
CREATE INDEX idx_sepsis_bundle_started ON sepsis_screenings(bundle_started_at);


-- =====================================================================
-- MODULE 9: Stroke / MI Fast-Track Module
-- =====================================================================
CREATE TABLE fast_track_activations (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                UUID NOT NULL REFERENCES visits(id),
    fast_track_type         VARCHAR(30) NOT NULL,
    status                  VARCHAR(30) NOT NULL DEFAULT 'ACTIVATED',
    activated_at            TIMESTAMP NOT NULL,
    activated_by_name       VARCHAR(255),

    -- Stroke fields (BE-FAST)
    symptom_onset_time      TIMESTAMP,
    be_fast_score           VARCHAR(255),
    nihss_score             INTEGER,
    ct_ordered_at           TIMESTAMP,
    ct_completed_at         TIMESTAMP,
    ct_result               TEXT,
    is_hemorrhagic          BOOLEAN,
    thrombolysis_eligible   BOOLEAN,
    thrombolysis_started_at TIMESTAMP,
    door_to_ct_minutes      INTEGER,

    -- MI/ACS fields
    chest_pain_onset_time   TIMESTAMP,
    ecg_ordered_at          TIMESTAMP,
    ecg_completed_at        TIMESTAMP,
    ecg_result              TEXT,
    st_elevation            BOOLEAN,
    troponin_ordered        BOOLEAN,
    troponin_result         DOUBLE PRECISION,
    troponin_resulted_at    TIMESTAMP,
    aspirin_given           BOOLEAN,
    aspirin_given_at        TIMESTAMP,
    anticoagulant_given     BOOLEAN,
    referred_for_pci        BOOLEAN,
    referred_for_pci_at     TIMESTAMP,
    door_to_ecg_minutes     INTEGER,
    door_to_needle_minutes  INTEGER,

    -- Outcome
    completed_at            TIMESTAMP,
    outcome                 TEXT,
    notes                   TEXT
);

CREATE INDEX idx_ft_visit ON fast_track_activations(visit_id);
CREATE INDEX idx_ft_type ON fast_track_activations(fast_track_type);
CREATE INDEX idx_ft_status ON fast_track_activations(status);
CREATE INDEX idx_ft_activated_at ON fast_track_activations(activated_at);
CREATE INDEX idx_ft_active ON fast_track_activations(is_active);


-- =====================================================================
-- MODULE 10: Hypoglycemia Enforcement Module
-- =====================================================================
CREATE TABLE hypoglycemia_events (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                UUID NOT NULL REFERENCES visits(id),
    detected_at             TIMESTAMP NOT NULL,
    glucose_level           DOUBLE PRECISION,
    trigger_reason          VARCHAR(255) NOT NULL,
    severity                VARCHAR(20) NOT NULL,
    treatment_given         TEXT,
    treatment_given_at      TIMESTAMP,
    treatment_given_by_name VARCHAR(255),
    repeat_glucose_level    DOUBLE PRECISION,
    repeat_glucose_at       TIMESTAMP,
    resolved                BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_at             TIMESTAMP,
    notes                   TEXT
);

CREATE INDEX idx_hypo_visit ON hypoglycemia_events(visit_id);
CREATE INDEX idx_hypo_severity ON hypoglycemia_events(severity);
CREATE INDEX idx_hypo_resolved ON hypoglycemia_events(resolved);
CREATE INDEX idx_hypo_detected_at ON hypoglycemia_events(detected_at);
CREATE INDEX idx_hypo_active ON hypoglycemia_events(is_active);


-- =====================================================================
-- MODULE 11: Infection Isolation & Public Health Module
-- =====================================================================
CREATE TABLE infection_screenings (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                    UUID NOT NULL REFERENCES visits(id),
    screened_at                 TIMESTAMP,
    screened_by_name            VARCHAR(255),
    risk_level                  VARCHAR(20),
    isolation_type              VARCHAR(20),
    suspected_condition         VARCHAR(255),
    notifiable_disease          VARCHAR(30),

    -- Symptom screening
    has_fever                   BOOLEAN NOT NULL DEFAULT FALSE,
    has_cough                   BOOLEAN NOT NULL DEFAULT FALSE,
    has_cough_duration_weeks    INTEGER,
    has_night_sweats            BOOLEAN NOT NULL DEFAULT FALSE,
    has_weight_loss             BOOLEAN NOT NULL DEFAULT FALSE,
    has_rash                    BOOLEAN NOT NULL DEFAULT FALSE,
    has_diarrhea                BOOLEAN NOT NULL DEFAULT FALSE,
    has_recent_travel           BOOLEAN NOT NULL DEFAULT FALSE,
    recent_travel_location      VARCHAR(255),
    has_contact_with_infectious BOOLEAN NOT NULL DEFAULT FALSE,
    contact_details             TEXT,
    has_bleeding_symptoms       BOOLEAN NOT NULL DEFAULT FALSE,
    is_healthcare_worker        BOOLEAN NOT NULL DEFAULT FALSE,

    -- PPE requirements
    requires_n95                BOOLEAN NOT NULL DEFAULT FALSE,
    requires_gown               BOOLEAN NOT NULL DEFAULT FALSE,
    requires_gloves             BOOLEAN NOT NULL DEFAULT FALSE,
    requires_face_shield        BOOLEAN NOT NULL DEFAULT FALSE,
    requires_apron              BOOLEAN NOT NULL DEFAULT FALSE,
    requires_boot_covers        BOOLEAN NOT NULL DEFAULT FALSE,

    -- Isolation management
    isolation_room_assigned     VARCHAR(255),
    isolation_started_at        TIMESTAMP,
    isolation_ended_at          TIMESTAMP,

    -- Public health notification
    public_health_notified_at       TIMESTAMP,
    public_health_reference_number  VARCHAR(255),

    notes                       TEXT
);

CREATE INDEX idx_inf_visit ON infection_screenings(visit_id);
CREATE INDEX idx_inf_risk_level ON infection_screenings(risk_level);
CREATE INDEX idx_inf_isolation_type ON infection_screenings(isolation_type);
CREATE INDEX idx_inf_notifiable ON infection_screenings(notifiable_disease);
CREATE INDEX idx_inf_screened_at ON infection_screenings(screened_at);
CREATE INDEX idx_inf_active ON infection_screenings(is_active);


-- =====================================================================
-- MODULE 12: Clinical Documentation System
-- =====================================================================
CREATE TABLE clinical_documents (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                UUID NOT NULL REFERENCES visits(id),
    document_type           VARCHAR(30) NOT NULL,
    title                   VARCHAR(255) NOT NULL,
    content                 TEXT NOT NULL,

    -- Legal compliance
    author_name             VARCHAR(255) NOT NULL,
    author_role             VARCHAR(255),
    author_license_number   VARCHAR(50),
    signed_at               TIMESTAMP,
    is_signed               BOOLEAN NOT NULL DEFAULT FALSE,
    co_signed_by_name       VARCHAR(255),
    co_signed_at            TIMESTAMP,

    -- Vitals snapshot at time of documentation
    vital_signs_id          UUID REFERENCES vital_signs(id),

    -- Amendment tracking
    is_amendment            BOOLEAN NOT NULL DEFAULT FALSE,
    amendment_reason        TEXT,
    original_document_id    UUID REFERENCES clinical_documents(id),
    amended_at              TIMESTAMP,

    template_used           VARCHAR(255),
    notes                   TEXT
);

CREATE INDEX idx_clin_doc_visit ON clinical_documents(visit_id);
CREATE INDEX idx_clin_doc_type ON clinical_documents(document_type);
CREATE INDEX idx_clin_doc_signed ON clinical_documents(is_signed);
CREATE INDEX idx_clin_doc_author ON clinical_documents(author_name);
CREATE INDEX idx_clin_doc_active ON clinical_documents(is_active);
CREATE INDEX idx_clin_doc_original ON clinical_documents(original_document_id);


-- =====================================================================
-- MODULE 13: Medication Safety Engine
-- =====================================================================
CREATE TABLE drug_formularies (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    generic_name                    VARCHAR(255) NOT NULL,
    brand_names                     TEXT,
    drug_class                      VARCHAR(255),
    atc_code                        VARCHAR(20),
    reml_category                   VARCHAR(255),

    -- Adult dosing
    adult_min_dose_mg               DOUBLE PRECISION,
    adult_max_dose_mg               DOUBLE PRECISION,
    adult_max_daily_dose_mg         DOUBLE PRECISION,

    -- Pediatric dosing (weight-based)
    pediatric_min_dose_mg_per_kg    DOUBLE PRECISION,
    pediatric_max_dose_mg_per_kg    DOUBLE PRECISION,
    pediatric_max_daily_dose_mg_per_kg DOUBLE PRECISION,

    -- Adjustments
    geriatric_adjustment_percent    DOUBLE PRECISION,
    renal_adjustment_required       BOOLEAN NOT NULL DEFAULT FALSE,
    hepatic_adjustment_required     BOOLEAN NOT NULL DEFAULT FALSE,

    -- Routes and interactions
    available_routes                VARCHAR(255),
    contraindications               TEXT,
    major_interactions              TEXT,
    allergen_groups                 TEXT,

    -- Safety flags
    is_high_alert                   BOOLEAN NOT NULL DEFAULT FALSE,
    requires_double_check           BOOLEAN NOT NULL DEFAULT FALSE,
    black_box_warning               TEXT,
    pregnancy_category              VARCHAR(5),
    is_on_reml                      BOOLEAN NOT NULL DEFAULT FALSE,

    -- Hospital scope (NULL = system-wide)
    hospital_id                     UUID REFERENCES hospitals(id)
);

CREATE INDEX idx_formulary_generic_name ON drug_formularies(generic_name);
CREATE INDEX idx_formulary_atc_code ON drug_formularies(atc_code);
CREATE INDEX idx_formulary_drug_class ON drug_formularies(drug_class);
CREATE INDEX idx_formulary_hospital ON drug_formularies(hospital_id);
CREATE INDEX idx_formulary_high_alert ON drug_formularies(is_high_alert);
CREATE INDEX idx_formulary_reml ON drug_formularies(is_on_reml);
CREATE INDEX idx_formulary_active ON drug_formularies(is_active);


CREATE TABLE medication_safety_checks (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                        UUID NOT NULL REFERENCES visits(id),
    medication_id                   UUID NOT NULL REFERENCES medication_administrations(id),
    checked_at                      TIMESTAMP NOT NULL,
    drug_name                       VARCHAR(255) NOT NULL,
    prescribed_dose_mg              DOUBLE PRECISION,
    patient_weight_kg               DOUBLE PRECISION,

    -- Check results
    allergy_check_passed            BOOLEAN NOT NULL DEFAULT TRUE,
    allergy_warning                 TEXT,
    dose_check_passed               BOOLEAN NOT NULL DEFAULT TRUE,
    dose_warning                    TEXT,
    interaction_check_passed        BOOLEAN NOT NULL DEFAULT TRUE,
    interaction_warning             TEXT,
    duplicate_therapy_check_passed  BOOLEAN NOT NULL DEFAULT TRUE,
    duplicate_warning               TEXT,
    overall_safe                    BOOLEAN NOT NULL DEFAULT TRUE,

    -- Override
    overridden_by                   VARCHAR(255),
    override_reason                 TEXT,
    overridden_at                   TIMESTAMP
);

CREATE INDEX idx_med_safety_visit ON medication_safety_checks(visit_id);
CREATE INDEX idx_med_safety_medication ON medication_safety_checks(medication_id);
CREATE INDEX idx_med_safety_overall ON medication_safety_checks(overall_safe);
CREATE INDEX idx_med_safety_checked_at ON medication_safety_checks(checked_at);
CREATE INDEX idx_med_safety_active ON medication_safety_checks(is_active);


-- =====================================================================
-- MODULE 14: Laboratory Integration Module
-- =====================================================================
CREATE TABLE lab_orders (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                        UUID NOT NULL REFERENCES visits(id),
    investigation_id                UUID REFERENCES investigations(id),
    order_number                    VARCHAR(30) NOT NULL UNIQUE,
    test_name                       VARCHAR(255) NOT NULL,
    test_code                       VARCHAR(50),
    priority                        VARCHAR(15) NOT NULL,
    ordered_at                      TIMESTAMP NOT NULL,
    ordered_by_name                 VARCHAR(255),
    specimen_type                   VARCHAR(50),

    -- Specimen lifecycle
    specimen_collected_at           TIMESTAMP,
    specimen_collected_by_name      VARCHAR(255),
    received_by_lab_at              TIMESTAMP,
    processing_started_at           TIMESTAMP,

    -- Results
    resulted_at                     TIMESTAMP,
    result_value                    TEXT,
    result_unit                     VARCHAR(50),
    result_numeric                  DOUBLE PRECISION,
    reference_range_min             DOUBLE PRECISION,
    reference_range_max             DOUBLE PRECISION,
    is_abnormal                     BOOLEAN NOT NULL DEFAULT FALSE,
    is_critical                     BOOLEAN NOT NULL DEFAULT FALSE,
    critical_value_type             VARCHAR(30),
    critical_value_notified_at      TIMESTAMP,
    critical_value_notified_to      VARCHAR(255),
    critical_value_acknowledged_at  TIMESTAMP,
    turnaround_minutes              INTEGER,

    -- Cancellation
    cancelled_at                    TIMESTAMP,
    cancelled_by_name               VARCHAR(255),
    cancel_reason                   VARCHAR(500),

    notes                           TEXT
);

CREATE INDEX idx_lab_order_visit ON lab_orders(visit_id);
CREATE INDEX idx_lab_order_number ON lab_orders(order_number);
CREATE INDEX idx_lab_order_priority ON lab_orders(priority);
CREATE INDEX idx_lab_order_critical ON lab_orders(is_critical);
CREATE INDEX idx_lab_order_ordered_at ON lab_orders(ordered_at);
CREATE INDEX idx_lab_order_resulted_at ON lab_orders(resulted_at);
CREATE INDEX idx_lab_order_active ON lab_orders(is_active);


-- =====================================================================
-- MODULE 15: Clinical Pathway Automation Engine
-- =====================================================================
CREATE TABLE clinical_pathways (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    pathway_code        VARCHAR(30) NOT NULL UNIQUE,
    pathway_name        VARCHAR(255) NOT NULL,
    category            VARCHAR(30) NOT NULL,
    description         TEXT,
    target_population   VARCHAR(30),
    protocol_version    VARCHAR(20),
    source_guideline    VARCHAR(500)
);

CREATE INDEX idx_pathway_code ON clinical_pathways(pathway_code);
CREATE INDEX idx_pathway_category ON clinical_pathways(category);
CREATE INDEX idx_pathway_active ON clinical_pathways(is_active);


CREATE TABLE pathway_steps (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    pathway_id          UUID NOT NULL REFERENCES clinical_pathways(id),
    step_order          INTEGER NOT NULL,
    step_title          VARCHAR(255) NOT NULL,
    step_description    TEXT NOT NULL,
    timeframe_minutes   INTEGER,
    is_mandatory        BOOLEAN NOT NULL DEFAULT TRUE,
    category            VARCHAR(30)
);

CREATE INDEX idx_step_pathway ON pathway_steps(pathway_id);
CREATE INDEX idx_step_order ON pathway_steps(step_order);
CREATE INDEX idx_step_active ON pathway_steps(is_active);


CREATE TABLE pathway_activations (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id            UUID NOT NULL REFERENCES visits(id),
    pathway_id          UUID NOT NULL REFERENCES clinical_pathways(id),
    activated_at        TIMESTAMP NOT NULL,
    activated_by_name   VARCHAR(255),
    completed_at        TIMESTAMP,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    deviation_reason    TEXT,
    notes               TEXT
);

CREATE INDEX idx_activation_visit ON pathway_activations(visit_id);
CREATE INDEX idx_activation_pathway ON pathway_activations(pathway_id);
CREATE INDEX idx_activation_status ON pathway_activations(status);
CREATE INDEX idx_activation_active ON pathway_activations(is_active);


CREATE TABLE pathway_step_completions (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    activation_id           UUID NOT NULL REFERENCES pathway_activations(id),
    step_id                 UUID NOT NULL REFERENCES pathway_steps(id),
    completed_at            TIMESTAMP,
    completed_by_name       VARCHAR(255),
    was_skipped             BOOLEAN NOT NULL DEFAULT FALSE,
    skip_reason             VARCHAR(500),
    notes                   TEXT,
    time_to_complete_minutes INTEGER
);

CREATE INDEX idx_step_completion_activation ON pathway_step_completions(activation_id);
CREATE INDEX idx_step_completion_step ON pathway_step_completions(step_id);
CREATE INDEX idx_step_completion_active ON pathway_step_completions(is_active);


-- =====================================================================
-- MODULE 16: ICU Escalation Logic
-- =====================================================================
CREATE TABLE icu_escalations (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                UUID NOT NULL REFERENCES visits(id),
    escalation_reason       TEXT NOT NULL,
    trigger_type            VARCHAR(30),
    escalated_at            TIMESTAMP,
    escalated_by_name       VARCHAR(255),
    is_automatic            BOOLEAN NOT NULL DEFAULT FALSE,

    -- ICU team notification
    icu_team_notified_at    TIMESTAMP,
    icu_consultant          VARCHAR(255),
    icu_responded_at        TIMESTAMP,
    icu_response_minutes    INTEGER,
    icu_bed_available       BOOLEAN,
    icu_bed_number          VARCHAR(50),
    icu_bed_assigned_at     TIMESTAMP,

    -- Stabilization
    stabilization_started_at TIMESTAMP,
    stabilization_notes     TEXT,
    intubation_required     BOOLEAN,
    vasopressors_required   BOOLEAN,
    mechanical_ventilation  BOOLEAN,

    -- Outcome
    status                  VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    decline_reason          TEXT,
    transferred_at          TIMESTAMP,
    alternative_plan        TEXT,
    outcome                 TEXT,
    notes                   TEXT
);

CREATE INDEX idx_icu_escalation_visit ON icu_escalations(visit_id);
CREATE INDEX idx_icu_escalation_status ON icu_escalations(status);
CREATE INDEX idx_icu_escalation_escalated_at ON icu_escalations(escalated_at);


-- =====================================================================
-- MODULE 17: Referral & Inter-Hospital Transfer Module
-- =====================================================================
CREATE TABLE referrals (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                    UUID NOT NULL REFERENCES visits(id),
    referral_type               VARCHAR(25) NOT NULL,
    status                      VARCHAR(35) NOT NULL DEFAULT 'INITIATED',
    referring_hospital_id       UUID NOT NULL REFERENCES hospitals(id),
    referring_clinician         VARCHAR(255) NOT NULL,
    referring_clinician_phone   VARCHAR(20),

    -- Receiving facility
    receiving_hospital_name     VARCHAR(255) NOT NULL,
    receiving_hospital_code     VARCHAR(20),
    receiving_clinician         VARCHAR(255),
    receiving_clinician_phone   VARCHAR(20),

    -- Clinical information
    referral_reason             TEXT NOT NULL,
    clinical_summary            TEXT NOT NULL,
    current_diagnosis           VARCHAR(500),
    current_triage_category     VARCHAR(10),
    current_tews_score          INTEGER,
    interventions_given         TEXT,
    ongoing_treatment           TEXT,

    -- Stabilization checklist (Rwanda referral standard)
    airway_secured              BOOLEAN,
    breathing_stable            BOOLEAN,
    circulation_stable          BOOLEAN,
    iv_access_established       BOOLEAN,
    medications_documented      BOOLEAN,
    allergies_documented        BOOLEAN,
    blood_type_documented       BOOLEAN,
    consent_obtained            BOOLEAN,
    referral_form_completed     BOOLEAN,
    patient_id_band_applied     BOOLEAN,

    -- Transport
    transport_mode              VARCHAR(20),
    escort_required             BOOLEAN,
    escort_name                 VARCHAR(255),
    escort_designation          VARCHAR(30),
    estimated_transfer_time_minutes INTEGER,
    departed_at                 TIMESTAMP,
    arrived_at                  TIMESTAMP,
    actual_transfer_time_minutes INTEGER,

    -- Timestamps
    initiated_at                TIMESTAMP,
    receiving_contacted_at      TIMESTAMP,
    accepted_at                 TIMESTAMP,
    stabilized_at               TIMESTAMP,
    completed_at                TIMESTAMP,

    -- Rwanda national references
    rhmis_case_number           VARCHAR(50),
    samu_request_number         VARCHAR(50),

    notes                       TEXT
);

CREATE INDEX idx_referral_visit ON referrals(visit_id);
CREATE INDEX idx_referral_referring_hospital ON referrals(referring_hospital_id);
CREATE INDEX idx_referral_status ON referrals(status);
CREATE INDEX idx_referral_type ON referrals(referral_type);
CREATE INDEX idx_referral_active ON referrals(is_active);
CREATE INDEX idx_referral_initiated_at ON referrals(initiated_at);


-- =====================================================================
-- MODULE 18: System Resilience & Offline Continuity Engine
-- =====================================================================
CREATE TABLE offline_sync_records (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    hospital_id             UUID NOT NULL REFERENCES hospitals(id),
    client_device_id        VARCHAR(255) NOT NULL,
    client_device_name      VARCHAR(255),
    entity_type             VARCHAR(50) NOT NULL,
    entity_id               UUID,
    operation_type          VARCHAR(10) NOT NULL,
    payload                 TEXT NOT NULL,
    sync_status             VARCHAR(15) NOT NULL DEFAULT 'PENDING',
    conflict_resolution     TEXT,
    created_offline_at      TIMESTAMP,
    synced_at               TIMESTAMP,
    error_message           TEXT
);

CREATE INDEX idx_sync_hospital ON offline_sync_records(hospital_id);
CREATE INDEX idx_sync_device ON offline_sync_records(client_device_id);
CREATE INDEX idx_sync_status ON offline_sync_records(sync_status);
CREATE INDEX idx_sync_entity_type ON offline_sync_records(entity_type);
CREATE INDEX idx_sync_entity_id ON offline_sync_records(entity_id);
CREATE INDEX idx_sync_active ON offline_sync_records(is_active);


CREATE TABLE system_health_statuses (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    hospital_id             UUID NOT NULL REFERENCES hospitals(id),
    check_time              TIMESTAMP,
    server_online           BOOLEAN NOT NULL DEFAULT TRUE,
    database_online         BOOLEAN NOT NULL DEFAULT TRUE,
    internet_connectivity   BOOLEAN NOT NULL DEFAULT TRUE,
    power_status            VARCHAR(20),
    last_successful_sync    TIMESTAMP,
    pending_sync_count      INTEGER NOT NULL DEFAULT 0,
    active_offline_devices  INTEGER NOT NULL DEFAULT 0,
    notes                   TEXT
);

CREATE INDEX idx_health_hospital ON system_health_statuses(hospital_id);
CREATE INDEX idx_health_check_time ON system_health_statuses(check_time);
CREATE INDEX idx_health_active ON system_health_statuses(is_active);


-- =====================================================================
-- MODULE 19: Patient Safety Incident Reporting System
-- =====================================================================
CREATE TABLE safety_incidents (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    hospital_id             UUID NOT NULL REFERENCES hospitals(id),
    visit_id                UUID REFERENCES visits(id),
    incident_number         VARCHAR(20) NOT NULL UNIQUE,
    incident_type           VARCHAR(35) NOT NULL,
    severity                VARCHAR(20) NOT NULL,
    status                  VARCHAR(35) NOT NULL DEFAULT 'REPORTED',
    incident_date_time      TIMESTAMP NOT NULL,
    location_in_hospital    VARCHAR(255),
    description             TEXT NOT NULL,
    contributing_factors    TEXT,
    immediate_actions       TEXT,

    -- Reporter info
    reported_by_name        VARCHAR(255) NOT NULL,
    reported_by_role        VARCHAR(255),
    reported_at             TIMESTAMP,
    involved_staff_names    TEXT,
    patient_harmed          BOOLEAN,

    -- Investigation
    investigator_name       VARCHAR(255),
    investigation_started_at TIMESTAMP,
    root_cause_analysis     TEXT,
    root_cause_category     VARCHAR(255),
    investigation_completed_at TIMESTAMP,

    -- Corrective actions
    corrective_action       TEXT,
    corrective_action_owner VARCHAR(255),
    corrective_action_deadline TIMESTAMP,
    corrective_action_completed_at TIMESTAMP,
    preventive_measures     TEXT,

    -- Closure
    closed_at               TIMESTAMP,
    closed_by_name          VARCHAR(255),
    lessons_learned         TEXT,
    is_anonymous            BOOLEAN NOT NULL DEFAULT FALSE,
    notes                   TEXT
);

CREATE INDEX idx_incident_hospital ON safety_incidents(hospital_id);
CREATE INDEX idx_incident_visit ON safety_incidents(visit_id);
CREATE INDEX idx_incident_number ON safety_incidents(incident_number);
CREATE INDEX idx_incident_type ON safety_incidents(incident_type);
CREATE INDEX idx_incident_severity ON safety_incidents(severity);
CREATE INDEX idx_incident_status ON safety_incidents(status);
CREATE INDEX idx_incident_datetime ON safety_incidents(incident_date_time);
CREATE INDEX idx_incident_active ON safety_incidents(is_active);


-- =====================================================================
-- MODULE 20: Report & Handover Module
-- =====================================================================
CREATE TABLE handover_reports (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    visit_id                UUID NOT NULL REFERENCES visits(id),
    hospital_id             UUID NOT NULL REFERENCES hospitals(id),
    report_type             VARCHAR(30) NOT NULL,
    generated_at            TIMESTAMP NOT NULL,
    generated_by_name       VARCHAR(255),

    -- Report sections
    patient_summary         TEXT,
    presenting_complaint    TEXT,
    triage_summary          TEXT,
    vital_signs_trend       TEXT,
    investigations_results  TEXT,
    diagnosis_summary       TEXT,
    treatment_summary       TEXT,
    active_clinical_alerts  TEXT,
    outstanding_tasks       TEXT,
    plan_of_care            TEXT,
    ed_timeline             TEXT,

    -- Handover acknowledgement
    received_by_name        VARCHAR(255),
    received_at             TIMESTAMP,
    acknowledged_at         TIMESTAMP,
    is_acknowledged         BOOLEAN NOT NULL DEFAULT FALSE,
    notes                   TEXT
);

CREATE INDEX idx_handover_visit ON handover_reports(visit_id);
CREATE INDEX idx_handover_hospital ON handover_reports(hospital_id);
CREATE INDEX idx_handover_type ON handover_reports(report_type);
CREATE INDEX idx_handover_generated_at ON handover_reports(generated_at);
CREATE INDEX idx_handover_acknowledged ON handover_reports(is_acknowledged);
CREATE INDEX idx_handover_active ON handover_reports(is_active);


-- =====================================================================
-- MODULE 21: Quality Metrics & Dashboard Engine
-- =====================================================================
CREATE TABLE quality_metric_snapshots (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    hospital_id                     UUID NOT NULL REFERENCES hospitals(id),
    snapshot_date                   DATE NOT NULL,
    snapshot_period                 VARCHAR(15) NOT NULL,

    -- Volume metrics
    total_patients                  INTEGER,
    total_admissions                INTEGER,
    total_discharges                INTEGER,
    total_transfers                 INTEGER,
    total_deaths                    INTEGER,
    total_left_without_being_seen   INTEGER,
    pediatric_patients              INTEGER,

    -- Triage metrics
    red_patients                    INTEGER,
    orange_patients                 INTEGER,
    yellow_patients                 INTEGER,
    green_patients                  INTEGER,
    blue_patients                   INTEGER,
    average_tews_score              DOUBLE PRECISION,
    retriage_count                  INTEGER,
    system_triggered_retriages      INTEGER,

    -- Time metrics
    average_wait_time_minutes       DOUBLE PRECISION,
    average_door_to_triage_minutes  DOUBLE PRECISION,
    average_door_to_physician_minutes DOUBLE PRECISION,
    average_total_ed_stay_minutes   DOUBLE PRECISION,
    percent_seen_within_target      DOUBLE PRECISION,
    median_wait_time_minutes        DOUBLE PRECISION,

    -- Clinical quality metrics
    sepsis_screening_rate           DOUBLE PRECISION,
    sepsis_bundle_compliance_rate   DOUBLE PRECISION,
    critical_lab_turnaround_minutes DOUBLE PRECISION,
    medication_error_count          INTEGER,
    safety_incident_count           INTEGER,

    -- Capacity metrics
    peak_ed_occupancy               INTEGER,
    average_ed_occupancy            DOUBLE PRECISION,
    icu_bed_utilization_percent     DOUBLE PRECISION,
    ed_bed_utilization_percent      DOUBLE PRECISION,

    -- Mortality metrics
    ed_mortality_rate               DOUBLE PRECISION,
    mortality_within_24_hours       INTEGER
);

CREATE INDEX idx_qms_hospital ON quality_metric_snapshots(hospital_id);
CREATE INDEX idx_qms_date ON quality_metric_snapshots(snapshot_date);
CREATE INDEX idx_qms_period ON quality_metric_snapshots(snapshot_period);
CREATE INDEX idx_qms_hospital_date ON quality_metric_snapshots(hospital_id, snapshot_date);
CREATE INDEX idx_qms_active ON quality_metric_snapshots(is_active);


-- =====================================================================
-- MODULE 22: AI-Assisted Risk Prediction Engine
-- =====================================================================
CREATE TABLE surge_predictions (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    hospital_id                 UUID NOT NULL REFERENCES hospitals(id),
    predicted_at                TIMESTAMP NOT NULL,
    prediction_horizon_hours    INTEGER NOT NULL,
    predicted_ed_admissions     INTEGER,
    predicted_icu_demand        INTEGER,
    predicted_red_patients      INTEGER,

    -- Current state
    current_ed_occupancy        INTEGER,
    current_icu_occupancy       INTEGER,
    ed_capacity                 INTEGER,
    icu_capacity                INTEGER,

    -- Risk assessment
    surge_risk_score            DOUBLE PRECISION,
    surge_risk_level            VARCHAR(15),
    historical_avg_for_period   DOUBLE PRECISION,
    current_arrival_rate        DOUBLE PRECISION,
    trend_direction             VARCHAR(20),
    seasonal_factor             DOUBLE PRECISION,

    -- Validation
    was_accurate                BOOLEAN,
    actual_value                INTEGER,

    notes                       TEXT
);

CREATE INDEX idx_surge_hospital ON surge_predictions(hospital_id);
CREATE INDEX idx_surge_predicted_at ON surge_predictions(predicted_at);
CREATE INDEX idx_surge_risk_level ON surge_predictions(surge_risk_level);
CREATE INDEX idx_surge_active ON surge_predictions(is_active);


-- =====================================================================
-- MODULE 23: National Health Data Reporting Interface
-- =====================================================================
CREATE TABLE moh_reports (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    hospital_id                     UUID NOT NULL REFERENCES hospitals(id),
    report_type                     VARCHAR(30) NOT NULL,
    report_period_start             TIMESTAMP NOT NULL,
    report_period_end               TIMESTAMP NOT NULL,
    generated_at                    TIMESTAMP,
    generated_by_name               VARCHAR(255),
    status                          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    submitted_at                    TIMESTAMP,
    submitted_by_name               VARCHAR(255),
    rejection_reason                TEXT,

    -- Aggregate statistics (de-identified)
    total_ed_visits                 INTEGER,
    total_triaged                   INTEGER,
    triage_category_breakdown       TEXT,
    average_wait_time_minutes       DOUBLE PRECISION,
    mortality_count                 INTEGER,
    left_without_being_seen_count   INTEGER,
    admission_count                 INTEGER,
    icu_admission_count             INTEGER,
    transfer_count                  INTEGER,
    top_diagnoses                   TEXT,
    top_chief_complaints            TEXT,
    pediatric_visit_count           INTEGER,
    malaria_positive_count          INTEGER,
    sepsis_screened_count           INTEGER,
    isolation_activated_count       INTEGER,
    average_length_of_stay_minutes  DOUBLE PRECISION,
    report_data_json                TEXT
);

CREATE INDEX idx_moh_report_hospital ON moh_reports(hospital_id);
CREATE INDEX idx_moh_report_type ON moh_reports(report_type);
CREATE INDEX idx_moh_report_period_start ON moh_reports(report_period_start);


-- =====================================================================
-- MODULE 24: Clinical Governance & Policy Control Engine
-- =====================================================================
CREATE TABLE clinical_policies (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    hospital_id             UUID REFERENCES hospitals(id),
    policy_type             VARCHAR(30) NOT NULL,
    policy_name             VARCHAR(255) NOT NULL,
    policy_code             VARCHAR(50),
    description             TEXT,
    policy_content          TEXT NOT NULL,
    effective_from          TIMESTAMP NOT NULL,
    effective_to            TIMESTAMP,
    policy_version          VARCHAR(20),

    -- Approval workflow
    status                  VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_by_name         VARCHAR(255),
    approved_by_name        VARCHAR(255),
    approved_at             TIMESTAMP,
    approval_notes          TEXT,

    -- Version tracking
    previous_version_id     UUID REFERENCES clinical_policies(id),
    change_reason           TEXT,
    notes                   TEXT
);

CREATE INDEX idx_clinical_policy_hospital ON clinical_policies(hospital_id);
CREATE INDEX idx_clinical_policy_type ON clinical_policies(policy_type);
CREATE INDEX idx_clinical_policy_active ON clinical_policies(is_active);


CREATE TABLE policy_audit_logs (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP,
    created_by      VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    version         BIGINT,

    policy_id           UUID NOT NULL REFERENCES clinical_policies(id),
    action              VARCHAR(30) NOT NULL,
    action_at           TIMESTAMP NOT NULL,
    action_by_name      VARCHAR(255) NOT NULL,
    previous_content    TEXT,
    new_content         TEXT,
    reason              TEXT
);

CREATE INDEX idx_policy_audit_policy ON policy_audit_logs(policy_id);
CREATE INDEX idx_policy_audit_action_at ON policy_audit_logs(action_at);
