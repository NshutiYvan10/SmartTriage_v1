-- V11: Add missing BaseEntity audit columns to shift_assignments
-- The V9 migration missed created_by, last_modified_by, and version columns
-- that are required by BaseEntity (which ShiftAssignment extends).

ALTER TABLE shift_assignments ADD COLUMN IF NOT EXISTS created_by VARCHAR(255);
ALTER TABLE shift_assignments ADD COLUMN IF NOT EXISTS last_modified_by VARCHAR(255);
ALTER TABLE shift_assignments ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
