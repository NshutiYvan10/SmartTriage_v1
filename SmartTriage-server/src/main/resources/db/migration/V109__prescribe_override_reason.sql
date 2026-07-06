-- V109: Mandatory justification for prescribe-despite-allergy / prescribe-despite-interaction.
--
-- The single most dangerous override in the system — prescribing a drug a patient
-- is documented allergic to (or against a known interaction) — recorded WHO, WHAT
-- allergen, and WHEN, but never WHY. Every other safety override in SmartTriage
-- carries a mandatory reason; this path only forced an acknowledgement. For a
-- life-critical incident record ("Dr X prescribed penicillin despite a documented
-- penicillin allergy") the clinical justification is exactly what an investigation
-- needs. These columns hold that reason; the service now rejects the override when
-- it is blank. Nullable in the schema (legacy rows + the common no-override case).

ALTER TABLE medication_administrations ADD COLUMN allergy_override_reason     TEXT;
ALTER TABLE medication_administrations ADD COLUMN interaction_override_reason TEXT;
