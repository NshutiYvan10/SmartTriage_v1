-- V38 — Pediatric triage form compliance.
--
-- Brings the data model in line with the official King Faisal Hospital
-- pediatric triage forms (Infant 0–3 and Child 3–12). Adds the
-- discriminators that exist on the KFH peds forms but are NOT on the
-- adult form, so the system stops conflating "adult VU" with "peds VU".
--
-- Background: prior to this migration the decision engine was checking
-- adult-form Very Urgent / Urgent fields when triaging children. The
-- adult fields cover items like Coughing/Vomiting blood, Aggression,
-- Shortness of breath — none of which appear on the KFH peds forms —
-- and miss items like Floppy/irritable, Tiny baby <2 months, and
-- Severe malnutrition/wasting that DO appear on the peds forms.
--
-- Changes are additive only: every new column is nullable / defaults
-- to FALSE, and existing flags continue to be used by the adult path.
-- The peds path will read the new flags via the updated decision
-- engine.
--
-- Convention: peds-form-only fields are prefixed `vu_peds_` or
-- `urg_peds_` to distinguish them at the column level from adult-form
-- fields. A peds patient with a positive sign on the peds form will
-- have the corresponding peds-prefixed flag set; the adult-prefixed
-- flag stays false. This keeps audit logs unambiguous.

ALTER TABLE triage_records
    -- ── Very Urgent — peds-form-only (Medical) ─────────────────
    ADD COLUMN vu_peds_more_sleepy_than_normal BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN vu_peds_inconsolable_severe_pain BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN vu_peds_floppy_irritable_restless BOOLEAN NOT NULL DEFAULT FALSE,
    -- Infant form only — not on the 3–12 child form. The decision
    -- engine clears this for non-infant visits (≥36 months) so a
    -- ticked-by-mistake flag doesn't false-positive on an older child.
    ADD COLUMN vu_peds_tiny_baby_under_2_months BOOLEAN NOT NULL DEFAULT FALSE,
    -- ── Very Urgent — peds-form-only (Trauma) ──────────────────
    -- Peds form burn threshold is 10% (vs adult 20%). Stored
    -- separately so audit can distinguish a peds 10–20% burn from
    -- an adult >20% burn, both of which yield Very Urgent.
    ADD COLUMN vu_peds_burn_over_10_percent BOOLEAN NOT NULL DEFAULT FALSE,
    -- ── Urgent — peds-form-only (Medical) ──────────────────────
    ADD COLUMN urg_peds_pitting_edema_face_or_feet BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN urg_peds_some_respiratory_distress BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN urg_peds_severe_malnutrition_wasting BOOLEAN NOT NULL DEFAULT FALSE,
    -- "Unwell with known diabetes" — peds urgent. Glucose value
    -- piggybacks on the existing urg_diabetic_glucose column rather
    -- than introducing a new one (same physiology, same number).
    ADD COLUMN urg_peds_unwell_with_known_diabetes BOOLEAN NOT NULL DEFAULT FALSE,
    -- Composite "Diarrhoea and/or vomiting plus any of: sunken eyes,
    -- dry mouth, decreased urine output, skin pinch slow but <2 sec".
    -- The triage form's checkbox is the composite; the four
    -- sub-items are tick-list options. We store the composite plus
    -- the four sub-flags so audit can reconstruct what the nurse saw.
    ADD COLUMN urg_peds_diarrhea_vomiting_dehydration BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN urg_peds_dehydration_sunken_eyes BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN urg_peds_dehydration_dry_mouth BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN urg_peds_dehydration_decreased_urine BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN urg_peds_dehydration_slow_skin_pinch BOOLEAN NOT NULL DEFAULT FALSE;
