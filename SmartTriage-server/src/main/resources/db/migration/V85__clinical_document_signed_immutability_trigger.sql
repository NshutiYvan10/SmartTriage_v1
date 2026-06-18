-- V85: Database-enforced immutability for SIGNED clinical documents.
--
-- A signed clinical document is a legal record. Application-layer guards
-- (entity @PreUpdate/@PreRemove) are the first line of defence; this trigger is
-- the backstop so that NO write path — including raw SQL or any future code that
-- bypasses the JPA entity — can silently alter or delete a signed record.
--
-- Rules once is_signed = TRUE:
--   * the row may NOT be deleted;
--   * its content/identity/lifecycle columns may NOT change, and it may NOT be
--     soft-deleted (is_active flipped to false);
--   * the ONLY permitted change is adding a single co-signature
--     (co_signed_by_* columns) plus framework audit columns (updated_at,
--     last_modified_by, version).
-- Corrections are made via an amendment (a new linked row), never by editing.

CREATE OR REPLACE FUNCTION enforce_signed_document_immutability()
    RETURNS trigger AS $$
BEGIN
    IF (TG_OP = 'DELETE') THEN
        IF OLD.is_signed THEN
            RAISE EXCEPTION 'Signed clinical document % cannot be deleted (legal record).', OLD.id;
        END IF;
        RETURN OLD;
    END IF;

    -- UPDATE
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
            OR NEW.template_used     IS DISTINCT FROM OLD.template_used) THEN
            RAISE EXCEPTION 'Signed clinical document % is immutable; only a co-signature may be added. Use an amendment for corrections.', OLD.id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_clinical_doc_immutable ON clinical_documents;
CREATE TRIGGER trg_clinical_doc_immutable
    BEFORE UPDATE OR DELETE ON clinical_documents
    FOR EACH ROW EXECUTE FUNCTION enforce_signed_document_immutability();
