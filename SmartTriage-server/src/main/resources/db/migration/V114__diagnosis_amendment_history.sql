-- V114 — Diagnosis edit-with-history (non-destructive amendment)
--
-- Editing a diagnosis no longer overwrites it. Instead a NEW diagnoses row is
-- created, linked back to the original via original_diagnosis_id, and the
-- superseded row is soft-deleted (is_active=false). Current-state reads keep
-- filtering is_active, so they show only the latest version, while the full
-- amendment chain remains retrievable for the history view / audit.

ALTER TABLE diagnoses
    ADD COLUMN IF NOT EXISTS original_diagnosis_id UUID,
    ADD COLUMN IF NOT EXISTS is_amendment BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS amendment_reason TEXT,
    ADD COLUMN IF NOT EXISTS amended_at TIMESTAMPTZ;

-- Self-referential FK to the root original version.
ALTER TABLE diagnoses
    ADD CONSTRAINT fk_diagnosis_original
    FOREIGN KEY (original_diagnosis_id) REFERENCES diagnoses (id);

CREATE INDEX IF NOT EXISTS idx_diagnosis_original ON diagnoses (original_diagnosis_id);
