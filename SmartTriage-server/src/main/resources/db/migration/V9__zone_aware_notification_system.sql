-- ======================================================================
-- V9: Zone-Aware Doctor Notification System
-- Adds shift_assignments table and escalation columns on clinical_alerts
-- ======================================================================

-- 1. Shift Assignments — maps staff to ED zones per shift
CREATE TABLE shift_assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hospital_id     UUID NOT NULL REFERENCES hospitals(id),
    shift_date      DATE NOT NULL,
    shift_period    VARCHAR(20) NOT NULL,           -- MORNING, AFTERNOON, NIGHT
    user_id         UUID NOT NULL REFERENCES users(id),
    zone            VARCHAR(20) NOT NULL,           -- RESUS, ACUTE, GENERAL, TRIAGE, OBSERVATION
    staff_role      VARCHAR(30) NOT NULL,           -- Role enum: DOCTOR, NURSE, TRIAGE_NURSE, etc.
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT uq_shift_user_date_period UNIQUE (user_id, shift_date, shift_period)
);

CREATE INDEX idx_shift_hospital_date_period ON shift_assignments(hospital_id, shift_date, shift_period);
CREATE INDEX idx_shift_user                 ON shift_assignments(user_id);
CREATE INDEX idx_shift_zone                 ON shift_assignments(zone);
CREATE INDEX idx_shift_active               ON shift_assignments(is_active);

-- 2. Add escalation columns to clinical_alerts
ALTER TABLE clinical_alerts ADD COLUMN target_zone          VARCHAR(20);
ALTER TABLE clinical_alerts ADD COLUMN escalation_tier      INTEGER NOT NULL DEFAULT 1;
ALTER TABLE clinical_alerts ADD COLUMN escalated_at         TIMESTAMP WITH TIME ZONE;
ALTER TABLE clinical_alerts ADD COLUMN target_doctor_id     UUID REFERENCES users(id);
ALTER TABLE clinical_alerts ADD COLUMN sats_target_minutes  INTEGER;

CREATE INDEX idx_alert_target_zone      ON clinical_alerts(target_zone);
CREATE INDEX idx_alert_escalation_tier  ON clinical_alerts(escalation_tier);
CREATE INDEX idx_alert_target_doctor    ON clinical_alerts(target_doctor_id);
