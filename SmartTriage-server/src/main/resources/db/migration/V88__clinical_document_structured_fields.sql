-- V88: Structured fields for high-stakes clinical documents (gap #6).
--
-- PROCEDURE_NOTE / OPERATIVE_NOTE and DEATH_CERTIFICATE were a single free-text
-- content blob behind an enum tag — the clinically/legally required elements
-- (operator, indication, findings, complications, outcome; cause/time/manner of
-- death) survived only as prose, if typed at all. This adds discrete, nullable
-- columns for those elements. They are populated only for the relevant document
-- types; other documents leave them NULL.

ALTER TABLE clinical_documents
    -- Procedure / operative note
    ADD COLUMN IF NOT EXISTS procedure_performed     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS procedure_indication    TEXT,
    ADD COLUMN IF NOT EXISTS procedure_findings      TEXT,
    ADD COLUMN IF NOT EXISTS procedure_complications TEXT,
    ADD COLUMN IF NOT EXISTS procedure_outcome       TEXT,
    ADD COLUMN IF NOT EXISTS procedure_performed_by  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS anaesthesia_type        VARCHAR(100),
    -- Death certificate
    ADD COLUMN IF NOT EXISTS time_of_death           TIMESTAMP,
    ADD COLUMN IF NOT EXISTS cause_of_death          TEXT,
    ADD COLUMN IF NOT EXISTS antecedent_causes       TEXT,
    ADD COLUMN IF NOT EXISTS manner_of_death         VARCHAR(40);

-- Extend the signed-document immutability trigger (V85) to freeze these new
-- columns too, so a signed procedure note or death certificate's structured
-- fields cannot be altered after signing.
CREATE OR REPLACE FUNCTION enforce_signed_document_immutability()
    RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        IF OLD.is_signed THEN
            RAISE EXCEPTION 'Signed clinical document % cannot be deleted (legal record).', OLD.id;
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.is_signed THEN
        IF (NEW.document_type        IS DISTINCT FROM OLD.document_type
            OR NEW.title             IS DISTINCT FROM OLD.title
            OR NEW.content           IS DISTINCT FROM OLD.content
            OR NEW.notes             IS DISTINCT FROM OLD.notes
            OR NEW.author_user_id    IS DISTINCT FROM OLD.author_user_id
            OR NEW.author_name       IS DISTINCT FROM OLD.author_name
            OR NEW.author_role       IS DISTINCT FROM OLD.author_role
            OR NEW.author_license_number IS DISTINCT FROM OLD.author_license_number
            OR NEW.signed_at         IS DISTINCT FROM OLD.signed_at
            OR NEW.is_signed         IS DISTINCT FROM OLD.is_signed
            OR NEW.is_active         IS DISTINCT FROM OLD.is_active
            OR NEW.visit_id          IS DISTINCT FROM OLD.visit_id
            OR NEW.is_amendment      IS DISTINCT FROM OLD.is_amendment
            OR NEW.amendment_reason  IS DISTINCT FROM OLD.amendment_reason
            OR NEW.original_document_id IS DISTINCT FROM OLD.original_document_id
            OR NEW.vital_signs_id    IS DISTINCT FROM OLD.vital_signs_id
            OR NEW.template_used     IS DISTINCT FROM OLD.template_used
            OR NEW.procedure_performed     IS DISTINCT FROM OLD.procedure_performed
            OR NEW.procedure_indication    IS DISTINCT FROM OLD.procedure_indication
            OR NEW.procedure_findings      IS DISTINCT FROM OLD.procedure_findings
            OR NEW.procedure_complications IS DISTINCT FROM OLD.procedure_complications
            OR NEW.procedure_outcome       IS DISTINCT FROM OLD.procedure_outcome
            OR NEW.procedure_performed_by  IS DISTINCT FROM OLD.procedure_performed_by
            OR NEW.anaesthesia_type        IS DISTINCT FROM OLD.anaesthesia_type
            OR NEW.time_of_death           IS DISTINCT FROM OLD.time_of_death
            OR NEW.cause_of_death          IS DISTINCT FROM OLD.cause_of_death
            OR NEW.antecedent_causes       IS DISTINCT FROM OLD.antecedent_causes
            OR NEW.manner_of_death         IS DISTINCT FROM OLD.manner_of_death) THEN
            RAISE EXCEPTION 'Signed clinical document % is immutable; only a co-signature may be added. Use an amendment for corrections.', OLD.id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
