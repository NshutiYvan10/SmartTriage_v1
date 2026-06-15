-- V73 — close the handover-completeness gaps with three new report sections.
--
-- An audit of the shift-handover report found that, while it captured the spine
-- of each domain, whole visit-linked clinical domains were never compiled into
-- the report text: the pre-hospital EMS run + interventions, the time-critical
-- acute protocols (fast-track stroke/STEMI, sepsis bundle, ICU escalation,
-- hypoglycaemia events, clinical-sign trajectory, care pathways), and the
-- formal clinical documents (procedure / operative / consultation / AMA /
-- consent notes). All of that data already exists in the system; these columns
-- give the generator somewhere to render it so an incoming clinician sees the
-- complete picture. Additive, nullable, snapshot text like the existing
-- section columns.

ALTER TABLE handover_reports
    ADD COLUMN prehospital_summary  TEXT,
    ADD COLUMN acute_protocols      TEXT,
    ADD COLUMN procedures_documents TEXT;

COMMENT ON COLUMN handover_reports.prehospital_summary IS
    'Pre-hospital / EMS run snapshot: MIST handover, field triage + vitals, lights, and pre-hospital interventions given. NULL if the patient did not arrive by ambulance.';
COMMENT ON COLUMN handover_reports.acute_protocols IS
    'Active time-critical protocols and acute events at generation time: fast-track (stroke/STEMI), sepsis screening + 1-hour bundle, ICU escalation, hypoglycaemia events, red-flag clinical-sign trajectory, and care-pathway activations.';
COMMENT ON COLUMN handover_reports.procedures_documents IS
    'Formal clinical documents on the visit: procedure / operative / consultation-referral / informed-consent / against-medical-advice / nursing-assessment notes.';
