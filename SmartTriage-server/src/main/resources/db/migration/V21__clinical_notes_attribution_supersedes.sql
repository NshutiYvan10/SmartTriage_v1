-- V21: Strengthen clinical_notes for legal-grade attribution and immutability.
--
-- Spec: every clinical note must be attributable to the clinician who wrote it
-- (audit-grade, not free-text), and notes must be non-editable once saved.
-- Corrections are handled via the supersede pattern — the original row is
-- never modified; a new row is created with supersedes_id = original.id.
--
-- author_user_id/author_role are populated server-side from the security
-- context, never from the client request body. Existing rows are left with
-- NULL values (legacy notes) and the columns are nullable to preserve them.

ALTER TABLE clinical_notes
    ADD COLUMN IF NOT EXISTS author_user_id UUID,
    ADD COLUMN IF NOT EXISTS author_role    VARCHAR(30),
    ADD COLUMN IF NOT EXISTS supersedes_id  UUID;

-- FK on author_user_id → users(id). ON DELETE SET NULL so a deleted user
-- doesn't cascade-delete clinical history; the note still stands, just with
-- the author reference cleared. RESTRICT on supersedes_id is correct: an
-- original note must not be deleted while a supersede chain references it.
ALTER TABLE clinical_notes
    ADD CONSTRAINT fk_clinical_note_author
        FOREIGN KEY (author_user_id) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_clinical_note_supersedes
        FOREIGN KEY (supersedes_id) REFERENCES clinical_notes(id) ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_clinical_note_author
    ON clinical_notes (author_user_id);

CREATE INDEX IF NOT EXISTS idx_clinical_note_supersedes
    ON clinical_notes (supersedes_id)
    WHERE supersedes_id IS NOT NULL;
