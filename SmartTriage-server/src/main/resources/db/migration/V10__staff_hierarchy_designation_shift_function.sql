-- V10: Staff Hierarchy — Designation on User + ShiftFunction on ShiftAssignment
-- Separates: Role (security) | Designation (professional title) | ShiftFunction (shift duty)

-- 1. Add designation column to users
ALTER TABLE users ADD COLUMN IF NOT EXISTS designation VARCHAR(50);

-- 2. Add new columns to shift_assignments
ALTER TABLE shift_assignments ADD COLUMN IF NOT EXISTS shift_function VARCHAR(30);
ALTER TABLE shift_assignments ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE shift_assignments ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP WITH TIME ZONE;

-- 3. Migrate existing staff_role data to shift_function
UPDATE shift_assignments
SET shift_function = CASE
    WHEN staff_role = 'DOCTOR'       THEN 'PRIMARY_DOCTOR'
    WHEN staff_role = 'NURSE'        THEN 'ZONE_NURSE'
    WHEN staff_role = 'TRIAGE_NURSE' THEN 'TRIAGE_NURSE'
    ELSE 'ZONE_NURSE'
END
WHERE shift_function IS NULL;

-- 4. Drop the old staff_role column
ALTER TABLE shift_assignments DROP COLUMN IF EXISTS staff_role;

-- 5. Set sensible default designations for existing users based on role
UPDATE users SET designation = 'MEDICAL_OFFICER'   WHERE role = 'DOCTOR'       AND designation IS NULL;
UPDATE users SET designation = 'STAFF_NURSE'       WHERE role = 'NURSE'        AND designation IS NULL;
UPDATE users SET designation = 'STAFF_NURSE'       WHERE role = 'TRIAGE_NURSE' AND designation IS NULL;
UPDATE users SET designation = 'LAB_TECHNICIAN'    WHERE role = 'LAB_TECH'     AND designation IS NULL;
UPDATE users SET designation = 'REGISTRAR'         WHERE role = 'REGISTRAR'    AND designation IS NULL;
UPDATE users SET designation = 'PARAMEDIC'         WHERE role = 'PARAMEDIC'    AND designation IS NULL;
UPDATE users SET designation = 'UNSPECIFIED'       WHERE designation IS NULL;
