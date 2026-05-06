-- ═══════════════════════════════════════════════════════════════
-- V22 — Guardian fields on patients.
--
-- BACKGROUND
-- ----------
-- The registration form has long captured guardian information for
-- pediatric patients (name, phone, relationship, national ID) but the
-- backend Patient entity has only emergency_contact_name / phone. The
-- frontend was silently dropping guardian-specific fields by squashing
-- guardian_name into emergency_contact_name; relationship and national ID
-- were lost entirely. This is a clinical-safety issue:
--   - For a pediatric patient, knowing who the guardian is (mother /
--     father / other relative) and how to reach them affects consent,
--     disposition, and child-protection escalation paths.
--   - The guardian's national ID is a legal record-keeping requirement.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- Adds four nullable columns to `patients`:
--   guardian_name, guardian_phone, guardian_relationship, guardian_national_id
--
-- Nullable on purpose: only pediatric patients have a guardian. For adults
-- these columns stay NULL and the existing emergency_contact_* columns
-- remain the primary contact.
--
-- WHY NOT A SEPARATE GUARDIANS TABLE
-- ----------------------------------
-- A patient has exactly one current legal guardian at the ED entry point.
-- Modeling guardian as a separate entity adds a join for every chart open
-- without buying us anything (we don't track guardian history). If that
-- requirement appears later (e.g. social-work case management), we can
-- promote these columns into a guardians table without touching app code
-- that reads patient.guardianName.
-- ═══════════════════════════════════════════════════════════════

ALTER TABLE patients ADD COLUMN IF NOT EXISTS guardian_name            VARCHAR(200);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS guardian_phone           VARCHAR(20);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS guardian_relationship    VARCHAR(50);
ALTER TABLE patients ADD COLUMN IF NOT EXISTS guardian_national_id     VARCHAR(30);

COMMENT ON COLUMN patients.guardian_name IS
    'Legal guardian for pediatric patients. Captured at registration. NULL for adults.';
COMMENT ON COLUMN patients.guardian_phone IS
    'Guardian contact phone. NULL for adults — see emergency_contact_phone.';
COMMENT ON COLUMN patients.guardian_relationship IS
    'Guardian relationship (mother, father, grandparent, other). NULL for adults.';
COMMENT ON COLUMN patients.guardian_national_id IS
    'Guardian national ID — legal record-keeping. NULL for adults.';
