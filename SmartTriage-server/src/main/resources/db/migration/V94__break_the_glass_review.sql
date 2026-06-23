-- V94: Governance review overlay for break-the-glass emergency overrides (Phase 3).
--
-- Phase 2 (V93) recorded each break-the-glass deep-record access as an IMMUTABLE forensic row
-- in break_the_glass_events. Phase 3 adds a real-time governance alert + a governance sign-off:
-- these columns are a REVIEW OVERLAY only — the forensic facts (actor, reason, prior_consent_state,
-- accessed_at) are never altered. A governance reviewer (admin / safety officer / charge nurse /
-- doctor) acknowledges that they have seen and reviewed the override.

ALTER TABLE break_the_glass_events
    ADD COLUMN acknowledged           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN acknowledged_by_user_id UUID,
    ADD COLUMN acknowledged_by_name    VARCHAR(255),
    ADD COLUMN acknowledged_at         TIMESTAMP,
    ADD COLUMN acknowledgment_note     TEXT;

-- Governance feed is scoped to the actor's hospital and ordered by access time.
CREATE INDEX idx_btg_actor_hospital ON break_the_glass_events (actor_hospital_id, accessed_at DESC);
