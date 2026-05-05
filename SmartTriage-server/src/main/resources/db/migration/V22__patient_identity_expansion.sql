-- V22: Expand patient identity surface for federated lookup.
--
-- Spec:
--   - Adults: national_id is the gold-standard anchor (16-digit Rwandan NID).
--   - Foreigners: passport_number.
--   - Pediatric (no NID yet): birth_certificate_number when issued, plus
--     guardian-mediated lookup (guardian_national_id, guardian_phone, etc.).
--
-- Two failure modes today that this migration closes:
--   1. national_id has only an index, no UNIQUE — so a race in
--      PatientService.createPatient can produce two rows with the same NID
--      in the same hospital.
--   2. The frontend already collects guardian_* fields at registration but
--      they are dropped on the floor server-side. Pediatric identity is
--      effectively unrecoverable across visits today.
--
-- Scope: per-hospital uniqueness only. Cross-hospital identity federation
-- is a future phase (a `patient_link` table joining matching rows across
-- hospitals) and would have to clear a consent / privacy bar that is out
-- of scope here.

ALTER TABLE patients
    ADD COLUMN IF NOT EXISTS passport_number          VARCHAR(30),
    ADD COLUMN IF NOT EXISTS birth_certificate_number VARCHAR(30),
    ADD COLUMN IF NOT EXISTS guardian_national_id     VARCHAR(30),
    ADD COLUMN IF NOT EXISTS guardian_phone           VARCHAR(20),
    ADD COLUMN IF NOT EXISTS guardian_name            VARCHAR(200),
    ADD COLUMN IF NOT EXISTS guardian_relationship    VARCHAR(50);

-- Partial UNIQUE indexes: NULL is allowed (kids may not have NID, foreigners
-- may not have NID, etc.) but if a value is set it must be unique within the
-- hospital among active rows. Soft-deleted rows are excluded so that a
-- re-registered NID after a soft-delete does not collide.
CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_nid_per_hospital
    ON patients (hospital_id, national_id)
    WHERE national_id IS NOT NULL AND is_active = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_passport_per_hospital
    ON patients (hospital_id, passport_number)
    WHERE passport_number IS NOT NULL AND is_active = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_birth_cert_per_hospital
    ON patients (hospital_id, birth_certificate_number)
    WHERE birth_certificate_number IS NOT NULL AND is_active = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_mrn_per_hospital
    ON patients (hospital_id, medical_record_number)
    WHERE medical_record_number IS NOT NULL AND is_active = TRUE;

-- Non-unique lookup indexes for tier-2/3 finders. Phones are not unique
-- (families share, sibling rows under the same guardian, etc.), so these are
-- BTREE only — the service ranks candidates and the UI picks one.
CREATE INDEX IF NOT EXISTS idx_patient_phone
    ON patients (hospital_id, phone_number)
    WHERE phone_number IS NOT NULL AND is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_patient_guardian_nid
    ON patients (hospital_id, guardian_national_id)
    WHERE guardian_national_id IS NOT NULL AND is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_patient_guardian_phone
    ON patients (hospital_id, guardian_phone)
    WHERE guardian_phone IS NOT NULL AND is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_patient_passport
    ON patients (hospital_id, passport_number)
    WHERE passport_number IS NOT NULL AND is_active = TRUE;

CREATE INDEX IF NOT EXISTS idx_patient_birth_cert
    ON patients (hospital_id, birth_certificate_number)
    WHERE birth_certificate_number IS NOT NULL AND is_active = TRUE;
