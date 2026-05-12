-- V56 — Precise doctor links on triage records.
--
-- Augment the existing free-text `notified_doctor_name` and
-- `attending_doctor_name` columns with FK links to the User rows for
-- those doctors. The frontend's new on-duty doctor picker captures the
-- user_id when the nurse picks from the dropdown; the legacy free-text
-- columns stay populated for the "Other / locum" fallback path.
--
-- Why both: a typo in a free-text doctor name was previously
-- unrecoverable. With the FK we can:
--   * route Tier 1 alerts to the specific user instead of zone-wide
--   * compute per-doctor response-time stats reliably
--   * survive doctor name changes (marriage, transliteration fix, ...)
--
-- ON DELETE SET NULL — deleting a User doesn't cascade-delete triage
-- history, just severs the link.

ALTER TABLE triage_records
    ADD COLUMN notified_doctor_user_id UUID NULL
        REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN attending_doctor_user_id UUID NULL
        REFERENCES users(id) ON DELETE SET NULL;

-- Partial indexes — most rows will be NULL (legacy data + locum
-- fallbacks); we only query when the link is set.
CREATE INDEX idx_triage_notified_doctor
    ON triage_records (notified_doctor_user_id)
    WHERE notified_doctor_user_id IS NOT NULL;

CREATE INDEX idx_triage_attending_doctor
    ON triage_records (attending_doctor_user_id)
    WHERE attending_doctor_user_id IS NOT NULL;
