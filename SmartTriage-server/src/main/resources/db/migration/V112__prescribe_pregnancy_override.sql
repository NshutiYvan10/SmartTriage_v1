-- V112: server-side pregnancy/teratogen prescribe gate (Phase 13c).
--
-- The teratogen check was previously a frontend-only dialog; the server merely
-- recorded a [teratogen] tag in the override reason. This makes it a real gate
-- (re-derived server-side from the patient's structured pregnancy status × the
-- drug), mirroring the allergy (V23) and interaction (V24/V109) override columns:
-- whether the prescriber overrode a pregnancy contraindication, when they
-- acknowledged it, and their mandatory documented reason.

ALTER TABLE medication_administrations
    ADD COLUMN prescribed_despite_pregnancy      BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE medication_administrations
    ADD COLUMN pregnancy_override_acknowledged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE medication_administrations
    ADD COLUMN pregnancy_override_reason         TEXT;
