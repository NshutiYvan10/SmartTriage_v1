-- V25: Structured pregnancy / lactation status on patients.
--
-- Background: Phase 13 added a teratogen safety check at prescribe
-- time. The check fires when the drug is on the FDA category D / X
-- list AND the patient's `chronic_conditions` text contains a
-- pregnancy / lactation marker ("pregnant", "lactating", etc.).
--
-- Free-text matching is a known-bad medium for safety triggers:
--   - "not pregnant" / "ruled out pregnancy" false-fire.
--   - Misspellings silently miss.
--   - Stale text never gets cleared after delivery.
--
-- Phase 13b replaces the primary trigger with a structured enum
-- column. The frontend safety check reads this column first and
-- falls back to the free-text scan when this column is null, so
-- existing patient records without structured data keep their
-- coverage during rollout.
--
-- Values (see PregnancyStatus.java for full rationale):
--   PREGNANT, BREASTFEEDING, POSSIBLY_PREGNANT,
--   NOT_PREGNANT, NOT_APPLICABLE, UNKNOWN
--
-- Stored as VARCHAR (Hibernate `EnumType.STRING`) for forward
-- compatibility — adding a new value doesn't require a migration.
--
-- Default is NULL rather than 'UNKNOWN' so the safety check can
-- distinguish "we asked and don't know" from "we never asked"
-- (the latter falls back to free-text scan; the former does not).
--
-- The `pregnancy_status_recorded_at` column captures when the value
-- was last set — useful for the safety officer reviewing whether a
-- "PREGNANT" tag is current or stale at prescribe time, and for
-- expiring stale flags in future automation.

ALTER TABLE patients
    ADD COLUMN pregnancy_status VARCHAR(32);

ALTER TABLE patients
    ADD COLUMN pregnancy_status_recorded_at TIMESTAMP WITH TIME ZONE;

-- No index — this column is read on the prescribe path scoped to a
-- single patient (already indexed via primary key) and isn't a
-- query predicate at population scale.

COMMENT ON COLUMN patients.pregnancy_status IS
    'Structured pregnancy / lactation status. NULL = never recorded; '
    'teratogen safety check falls back to free-text chronic_conditions scan when NULL.';

COMMENT ON COLUMN patients.pregnancy_status_recorded_at IS
    'When pregnancy_status was last set. Allows the safety officer to '
    'spot stale flags and lets future automation expire them.';
