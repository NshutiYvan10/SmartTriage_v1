-- V78 — Infection isolation: action trail (actor + timestamps), placement clock,
-- clearance reason, and two new screening inputs (immunocompromised → PROTECTIVE
-- isolation; neck stiffness → meningococcal). Closes the audit findings that
-- assign-room/end recorded no actor, there was no escalation clock, and PROTECTIVE
-- isolation was unreachable.

ALTER TABLE infection_screenings
    ADD COLUMN is_immunocompromised          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN has_neck_stiffness            BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN isolation_room_assigned_at    TIMESTAMPTZ,
    ADD COLUMN isolation_assigned_by_name    VARCHAR(255),
    ADD COLUMN placement_due_at              TIMESTAMPTZ,
    ADD COLUMN isolation_ended_by_name       VARCHAR(255),
    ADD COLUMN isolation_end_reason          TEXT,
    ADD COLUMN public_health_notified_by_name VARCHAR(255);

-- Speeds the placement monitor's scan for flagged-but-unroomed isolations.
CREATE INDEX IF NOT EXISTS idx_inf_placement_due ON infection_screenings (placement_due_at)
    WHERE isolation_type IS NOT NULL AND isolation_ended_at IS NULL AND isolation_room_assigned IS NULL;
