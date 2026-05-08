-- V50 — Paramedic / EMS workflow Phase 1
--
-- Captures the pre-hospital story: vitals on scene, paramedic's
-- field triage, treatments given, and the moment the ED nurse takes
-- over. Today the system can record "patient arrived by ambulance"
-- but knows nothing about what happened in the back of the truck —
-- so the doctor at the door starts blind. This closes that gap.
--
-- One ems_runs row per ambulance dispatch. Itemised treatments live
-- in ems_interventions (oxygen, IV, drugs, defib shocks). Pre-hospital
-- vitals re-use the existing vital_streams table tagged with
-- VitalSource.AMBULANCE_MONITOR — keeping the trend continuous from
-- field → ED is more useful than a separate table.

CREATE TABLE ems_runs (
    -- BaseEntity columns
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,

    hospital_id UUID NOT NULL REFERENCES hospitals(id),
    visit_id UUID REFERENCES visits(id),
    paramedic_user_id UUID REFERENCES users(id),
    paramedic_name VARCHAR(255),

    -- The dispatch identity
    service VARCHAR(40) NOT NULL DEFAULT 'OTHER',          -- SAMU, HOSPITAL, PRIVATE, OTHER
    unit_callsign VARCHAR(40),

    -- Run timeline
    dispatched_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    scene_arrived_at TIMESTAMP WITH TIME ZONE,
    scene_left_at TIMESTAMP WITH TIME ZONE,
    ed_arrived_at TIMESTAMP WITH TIME ZONE,
    handed_off_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    cancel_reason VARCHAR(500),

    -- Patient context (free-text — the formal Patient row may be
    -- created later by the registrar)
    patient_age_years INT,
    patient_sex VARCHAR(10),                                -- MALE/FEMALE/UNKNOWN
    incident_location VARCHAR(255),
    mechanism VARCHAR(500),
    history_summary TEXT,
    injuries_observed TEXT,

    -- Field triage call
    field_triage_category VARCHAR(20),                      -- RED/ORANGE/YELLOW/GREEN/BLUE
    field_triage_reason VARCHAR(500),

    -- Field vitals (snapshot at scene-arrival; serial vitals go to
    -- vital_streams keyed by visit_id)
    field_gcs INT,
    field_resp_rate INT,
    field_hr INT,
    field_sbp INT,
    field_dbp INT,
    field_spo2 INT,
    field_temp NUMERIC(4,1),
    field_glucose NUMERIC(5,2),

    -- Workflow state — the lab module's explicit-status pattern is
    -- worth reusing here so illegal transitions throw cleanly.
    status VARCHAR(30) NOT NULL DEFAULT 'DISPATCHED',
    CONSTRAINT ems_run_status_chk CHECK (status IN
        ('DISPATCHED','EN_ROUTE','ARRIVED','HANDED_OFF','CANCELLED')),

    -- Transfer of care
    handed_off_to_user_id UUID REFERENCES users(id),
    handed_off_to_name VARCHAR(255),
    handover_acknowledgement_text TEXT,

    -- ETA in minutes (paramedic can update; null when unknown)
    eta_minutes INT,

    notes TEXT
);

CREATE INDEX idx_ems_run_hospital_status ON ems_runs(hospital_id, status) WHERE is_active = TRUE;
CREATE INDEX idx_ems_run_visit ON ems_runs(visit_id) WHERE visit_id IS NOT NULL;
CREATE INDEX idx_ems_run_paramedic ON ems_runs(paramedic_user_id) WHERE paramedic_user_id IS NOT NULL;
CREATE INDEX idx_ems_run_dispatched_at ON ems_runs(dispatched_at);

-- Itemised pre-hospital interventions. The free-text `detail` keeps
-- this usable today; Phase 2 can add a curated catalog if needed.
CREATE TABLE ems_interventions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,

    ems_run_id UUID NOT NULL REFERENCES ems_runs(id) ON DELETE CASCADE,

    type VARCHAR(40) NOT NULL,
    CONSTRAINT ems_intervention_type_chk CHECK (type IN
        ('OXYGEN','IV_ACCESS','FLUID','MEDICATION','DEFIBRILLATION',
         'AIRWAY','IMMOBILISATION','SPLINTING','TOURNIQUET','CPR','OTHER')),

    given_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    given_by_name VARCHAR(255),

    detail VARCHAR(500),
    dose VARCHAR(60),
    route VARCHAR(20),
    outcome VARCHAR(255),
    notes TEXT
);

CREATE INDEX idx_ems_intervention_run ON ems_interventions(ems_run_id) WHERE is_active = TRUE;
CREATE INDEX idx_ems_intervention_given_at ON ems_interventions(given_at);

-- Visit gets back-references so the visit detail page can render
-- the pre-hospital tab in one query.
ALTER TABLE visits ADD COLUMN ems_run_id UUID REFERENCES ems_runs(id);
ALTER TABLE visits ADD COLUMN field_triage_category VARCHAR(20);

-- 15-minute ED re-triage clock. Set at confirm-arrival; cleared
-- when the ED nurse files a TriageRecord. Scheduler scans rows
-- where this is in the past and no triage record exists, and
-- fires FIELD_TRIAGED_AWAITING_REVIEW alerts.
ALTER TABLE visits ADD COLUMN ed_retriage_due_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_visit_retriage_due ON visits(ed_retriage_due_at)
    WHERE ed_retriage_due_at IS NOT NULL AND is_active = TRUE;
