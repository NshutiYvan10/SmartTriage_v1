-- ═══════════════════════════════════════════════════════════════
-- V68 — column-type correction for V67's Double-mapped fields.
--
-- V67 declared several columns NUMERIC(p,s) whose entity fields are
-- java.lang.Double. Hibernate's schema validation maps Double to
-- PostgreSQL double precision (float53) and refuses to boot against
-- NUMERIC ("wrong column type encountered in column [gate_threshold]").
-- BigDecimal fields (dose_value) correctly stay NUMERIC.
--
-- NUMERIC → double precision casts implicitly; no data is touched
-- (these columns are brand-new in V67 and at most hours old anywhere).
-- ═══════════════════════════════════════════════════════════════

ALTER TABLE medication_administrations
    ALTER COLUMN interval_hours          TYPE double precision,
    ALTER COLUMN prn_min_interval_hours  TYPE double precision,
    ALTER COLUMN gate_threshold          TYPE double precision,
    ALTER COLUMN rate_value              TYPE double precision;

ALTER TABLE medication_doses
    ALTER COLUMN rate_value              TYPE double precision;
