-- V107: Patient-centric audit + DB-enforced append-only audit log.
--
-- (1) WHO-DID-WHAT-TO-WHOM: audit_logs rows carried actor + endpoint but NO link to
--     the patient/visit the action touched. Mutations keyed by a resource id (a
--     hypoglycemia event id, an isolation screening id, a pathway activation id...)
--     were invisible to any patient-centric question — the exact question incident
--     reporting asks ("who made the decisions that led to this patient's condition?").
--     visit_id/patient_id are resolved at write time from the request's authorization
--     context (ClinicalAuthz already resolves which visit every clinical endpoint
--     touches) and stamped here. Partial indexes: most non-clinical rows stay NULL.
--
-- (2) APPEND-ONLY, ENFORCED: "insert-only" was a code convention. For a medico-legal
--     record that must survive an incident investigation, the DB now refuses UPDATE
--     and DELETE outright — a compromised app account or an ad-hoc script cannot
--     rewrite history. (Retention/archival policies, if ever needed, must be done by
--     a DBA deliberately dropping the trigger first.)

ALTER TABLE audit_logs ADD COLUMN visit_id   UUID;
ALTER TABLE audit_logs ADD COLUMN patient_id UUID;

CREATE INDEX idx_audit_visit   ON audit_logs (visit_id)   WHERE visit_id   IS NOT NULL;
CREATE INDEX idx_audit_patient ON audit_logs (patient_id) WHERE patient_id IS NOT NULL;

CREATE OR REPLACE FUNCTION audit_logs_block_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only (medico-legal record) — % is blocked', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_block_mutation();
