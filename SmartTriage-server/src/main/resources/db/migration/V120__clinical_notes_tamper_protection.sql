-- V120: database-level tamper protection for clinical_notes.
--
-- Clinical notes are legal-grade records. The application layer is already
-- append-only (no update endpoint; corrections write a new row via
-- supersedes_id), but until now nothing STOPPED a stray UPDATE from a bug,
-- a migration, or an ad-hoc psql session — audit_logs got this guarantee
-- in V107; the clinical narrative deserves the same.
--
-- Rules enforced here:
--   * DELETE is blocked outright (soft-delete only).
--   * UPDATE may only touch housekeeping columns (is_active for soft-delete,
--     version / updated_at / last_modified_by which JPA maintains). Every
--     clinical column — content, type, section, attribution, timestamps,
--     the supersede pointer, the visit linkage — is frozen at insert.
--
-- Idempotent (OR REPLACE / IF EXISTS) so a manually-applied copy and the
-- Flyway run converge on the same objects.

CREATE OR REPLACE FUNCTION clinical_notes_block_tamper() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'clinical_notes is append-only (legal-grade record) — DELETE is blocked; soft-delete via is_active';
    END IF;

    IF NEW.content            IS DISTINCT FROM OLD.content
       OR NEW.note_type        IS DISTINCT FROM OLD.note_type
       OR NEW.section          IS DISTINCT FROM OLD.section
       OR NEW.recorded_at      IS DISTINCT FROM OLD.recorded_at
       OR NEW.recorded_by_name IS DISTINCT FROM OLD.recorded_by_name
       OR NEW.author_user_id   IS DISTINCT FROM OLD.author_user_id
       OR NEW.author_role      IS DISTINCT FROM OLD.author_role
       OR NEW.supersedes_id    IS DISTINCT FROM OLD.supersedes_id
       OR NEW.visit_id         IS DISTINCT FROM OLD.visit_id
       OR NEW.created_at       IS DISTINCT FROM OLD.created_at
       OR NEW.created_by       IS DISTINCT FROM OLD.created_by
    THEN
        RAISE EXCEPTION 'clinical_notes is append-only (legal-grade record) — clinical columns cannot be modified; write a superseding note instead';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_clinical_notes_tamper ON clinical_notes;
CREATE TRIGGER trg_clinical_notes_tamper
    BEFORE UPDATE OR DELETE ON clinical_notes
    FOR EACH ROW EXECUTE FUNCTION clinical_notes_block_tamper();
