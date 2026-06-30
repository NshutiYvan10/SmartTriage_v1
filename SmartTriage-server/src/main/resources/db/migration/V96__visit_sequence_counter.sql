-- V96 — Durable, restart-proof visit-number sequence.
--
-- BUG THIS FIXES: visit numbers were minted from an in-memory AtomicLong that
-- resets to 0 on every application restart, while visits.visit_number is
-- NOT NULL UNIQUE. After a restart the generator re-issued V-<code>-<date>-00001,
-- 00002, … — numbers that already existed from earlier the SAME day — so the
-- visit INSERT hit the unique constraint and the whole transaction rolled back
-- (the user saw a generic 409 "conflicts with existing data", yet the EMS run,
-- created in an EARLIER transaction, still showed in Active runs). This broke
-- ALL registration paths (walk-in, EMS preregister, Direct Resus) after a restart
-- until the in-memory counter climbed back past the day's existing count.
--
-- FIX: a per-(hospital, day) counter persisted in the database and incremented
-- with Postgres's atomic INSERT … ON CONFLICT … DO UPDATE … RETURNING, exactly
-- like unidentified_patient_counters (V44). The value survives restarts and the
-- increment is serialised by the row lock, so two concurrent registrations can
-- never draw the same number. Keyed on the hospital CODE (the same token used in
-- the visit-number string), so no entity/FK plumbing is needed in the generator.
CREATE TABLE IF NOT EXISTS visit_sequence_counters (
    hospital_code VARCHAR(40)  NOT NULL,
    sequence_date DATE         NOT NULL,
    next_index    BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (hospital_code, sequence_date)
);

COMMENT ON TABLE visit_sequence_counters IS
    'Per-hospital, per-day monotonic counter feeding visits.visit_number '
    '(V-<code>-<yyyyMMdd>-<00000>). Replaces the old in-memory AtomicLong that '
    'reset on restart and collided with same-day visit numbers. Incremented '
    'atomically (INSERT … ON CONFLICT … RETURNING); a new row per day implicitly resets.';

-- Seed today's counter from the high-water mark of visit numbers ALREADY issued
-- today, so the first post-deploy registration continues ABOVE the in-memory
-- numbers minted earlier today rather than colliding with them. Parses the
-- trailing 5-digit sequence out of V-<code>-<yyyyMMdd>-<seq>. Idempotent: only
-- inserts a row where none exists yet for (code, today).
INSERT INTO visit_sequence_counters (hospital_code, sequence_date, next_index, updated_at)
SELECT
    h.hospital_code,
    CURRENT_DATE,
    COALESCE(MAX(CAST(RIGHT(v.visit_number, 5) AS BIGINT)), 0),
    CURRENT_TIMESTAMP
FROM hospitals h
JOIN visits v
    ON v.hospital_id = h.id
   AND v.visit_number ~ ('^V-' || h.hospital_code || '-' || TO_CHAR(CURRENT_DATE, 'YYYYMMDD') || '-[0-9]{5}$')
GROUP BY h.hospital_code
ON CONFLICT (hospital_code, sequence_date) DO NOTHING;
