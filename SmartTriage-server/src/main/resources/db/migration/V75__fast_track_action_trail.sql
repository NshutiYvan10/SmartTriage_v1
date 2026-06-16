-- V75 — Fast Track action trail + thrombolysis advisory
--
-- The fast-track audit found the activation had no acknowledgement model and no
-- per-transition actor (activatedByName was client-supplied free text), and the
-- thrombolysis "eligible" flag read as a definitive verdict with no advisory
-- context. These columns add an owned, time-stamped action trail and a
-- human-readable thrombolysis advisory string (the system flags the window; the
-- clinician confirms contraindications).

ALTER TABLE fast_track_activations
    ADD COLUMN acknowledged_at        TIMESTAMPTZ,
    ADD COLUMN acknowledged_by_name   VARCHAR(255),
    ADD COLUMN completed_by_name      VARCHAR(255),
    ADD COLUMN last_updated_by_name   VARCHAR(255),
    ADD COLUMN thrombolysis_advisory  TEXT;
