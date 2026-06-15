-- V71 — persist the identity-resolution note for medico-legal audit.
--
-- ResolveIdentityRequest already accepts a resolutionNote ("Family arrived
-- with ID", "Patient woke and gave name") and the UI sends it, but it was
-- only written to a log line — never stored on the patient row, so the
-- reason a Red-patient placeholder was named/merged was not queryable and
-- was lost on restart. This column closes that audit gap. Additive, nullable.

ALTER TABLE patients
    ADD COLUMN resolution_note VARCHAR(500);

COMMENT ON COLUMN patients.resolution_note IS
    'Why/how an unidentified placeholder was resolved (typed identity or merge). Audit trail alongside identified_at / identified_by.';
