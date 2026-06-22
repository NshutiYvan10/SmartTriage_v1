-- V92: cross-hospital patient identity (Phase 1 of the federated-hybrid model).
--
-- A PersonIdentity is the SHARED anchor across SmartTriage hospitals, keyed on national ID.
-- Each hospital still owns its own local `patients` row (deep records stay hospital-owned);
-- the new patients.person_identity_id links those local rows to one shared identity so a
-- returning patient at a different hospital is recognised instead of re-registered blank.
--
-- Phase 1 shares only identity + the minimal safety summary (assembled at read time from the
-- linked patients). Deep-record sharing, consent, and break-the-glass are later phases.

CREATE TABLE person_identities (
    id               UUID PRIMARY KEY,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP,
    created_by       VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    version          BIGINT,
    national_id      VARCHAR(30) NOT NULL,
    CONSTRAINT uq_person_identity_national_id UNIQUE (national_id)
);

ALTER TABLE patients ADD COLUMN person_identity_id UUID;
ALTER TABLE patients ADD CONSTRAINT fk_patient_person_identity
    FOREIGN KEY (person_identity_id) REFERENCES person_identities(id);
CREATE INDEX idx_patient_person_identity ON patients (person_identity_id);

-- Backfill: one shared identity per DISTINCT non-blank national ID, then link existing
-- patients. Pre-existing same-national-ID rows across hospitals collapse onto one identity
-- (the intended cross-hospital link). Null/blank-NID patients (incl. unidentified placeholders)
-- are left unlinked. version=0 so Hibernate optimistic locking is satisfied on first update.
INSERT INTO person_identities (id, created_at, is_active, version, national_id)
SELECT gen_random_uuid(), now(), TRUE, 0, t.national_id
FROM (SELECT DISTINCT TRIM(national_id) AS national_id
      FROM patients
      WHERE national_id IS NOT NULL AND TRIM(national_id) <> '') t;

UPDATE patients p
SET person_identity_id = pi.id
FROM person_identities pi
WHERE pi.national_id = TRIM(p.national_id)
  AND p.national_id IS NOT NULL AND TRIM(p.national_id) <> '';
