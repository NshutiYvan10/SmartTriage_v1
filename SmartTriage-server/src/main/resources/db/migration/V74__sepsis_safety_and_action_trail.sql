-- V74 — sepsis screening hardening: pediatric safety, data-quality flagging,
-- and a time-stamped 1-hour-bundle action trail.
--
-- Driven by the zero-tolerance sepsis audit:
--   * Pediatric: the engine applied adult qSOFA/SIRS thresholds to children
--     with no caveat. We now age-band the SIRS vital thresholds and stamp a
--     mandatory caveat so an adult-scored result is never presented as
--     authoritative/reassuring for a child.
--   * Data quality: missing individual vitals silently under-scored with no
--     warning; we now flag when the screen ran on insufficient data so a
--     negative on sparse vitals is not read as a clean bill of health.
--   * Action trail: the 6 bundle items were bare booleans with no timing or
--     actor — inadequate for a medico-legal 1-hour-bundle record. We add who
--     ran the bundle and WHEN each item was completed.
-- All additive + nullable / defaulted.

ALTER TABLE sepsis_screenings
    ADD COLUMN is_pediatric        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pediatric_caveat    TEXT,
    ADD COLUMN insufficient_data   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN data_quality_note   TEXT,
    ADD COLUMN bundle_started_by_name   VARCHAR(255),
    ADD COLUMN bundle_completed_by_name VARCHAR(255),
    ADD COLUMN blood_culture_obtained_at        TIMESTAMP WITH TIME ZONE,
    ADD COLUMN broad_spectrum_antibiotics_at    TIMESTAMP WITH TIME ZONE,
    ADD COLUMN iv_crystalloid_bolus_at          TIMESTAMP WITH TIME ZONE,
    ADD COLUMN lactate_measured_at              TIMESTAMP WITH TIME ZONE,
    ADD COLUMN vasopressors_if_needed_at        TIMESTAMP WITH TIME ZONE,
    ADD COLUMN repeat_lactate_if_elevated_at    TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN sepsis_screenings.pediatric_caveat IS
    'Non-null for pediatric screens: states that age-adjusted SIRS was applied and adult qSOFA hypotension thresholds are not validated for children (compensated pediatric shock can be normotensive). Surfaced in API + UI.';
COMMENT ON COLUMN sepsis_screenings.insufficient_data IS
    'True when key scoring vitals (temp/HR/RR/SBP/mentation) were missing, so a negative result must not be read as reassuring.';
