-- V84: Bind clinical-document authorship and signatures to the authenticated user.
--
-- The clinical_documents module previously recorded author_name, author_role,
-- author_license_number and co_signed_by_name as client-supplied free text taken
-- straight from the request body — any role-holder could author or "sign" a
-- discharge summary, consent form or death certificate under any clinician's name
-- and license number. These are legally significant documents; the signature must
-- be attributable to the real, authenticated user.
--
-- This adds FK columns to users(id) for the author and co-signer. The existing
-- name/role/license columns are retained as IMMUTABLE SNAPSHOTS that the service
-- now populates from the resolved User's own record (never from the request), so
-- a printed signature stays stable even if the user's profile later changes.
--
-- ON DELETE SET NULL: deleting a user must never cascade-delete clinical history;
-- the document still stands, with the user reference cleared (the snapshot name
-- preserves who signed). Legacy rows keep NULL user ids and the columns are
-- nullable to preserve them.

ALTER TABLE clinical_documents
    ADD COLUMN IF NOT EXISTS author_user_id              UUID,
    ADD COLUMN IF NOT EXISTS co_signed_by_user_id        UUID,
    ADD COLUMN IF NOT EXISTS co_signed_by_role           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS co_signed_by_license_number VARCHAR(50);

ALTER TABLE clinical_documents
    ADD CONSTRAINT fk_clin_doc_author_user
        FOREIGN KEY (author_user_id) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_clin_doc_cosigner_user
        FOREIGN KEY (co_signed_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_clin_doc_author_user
    ON clinical_documents (author_user_id);

CREATE INDEX IF NOT EXISTS idx_clin_doc_cosigner_user
    ON clinical_documents (co_signed_by_user_id)
    WHERE co_signed_by_user_id IS NOT NULL;
