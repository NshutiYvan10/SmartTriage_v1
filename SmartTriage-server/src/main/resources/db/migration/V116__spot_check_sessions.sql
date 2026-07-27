-- V116: General-zone scheduled vitals rechecks — spot-check session kind.
--
-- A SPOT_CHECK session is a short-lived device↔visit binding used by the
-- roaming obs-round monitor in chair-based zones (GENERAL/AMBULATORY):
-- the nurse wheels the shared monitor to the patient, a spot-check session
-- opens, and the session self-completes once one validated full vitals set
-- has been captured (or times out after 10 minutes). Completion creates a
-- clinical VitalSigns snapshot, which is what resets the patient's
-- reassessment clock. CONTINUOUS is the existing bed-monitor behaviour and
-- the default for all historical rows.
ALTER TABLE device_sessions
    ADD COLUMN session_type VARCHAR(16) NOT NULL DEFAULT 'CONTINUOUS';

-- The 10-minute timeout sweep scans only live spot-checks.
CREATE INDEX idx_device_session_spot_active
    ON device_sessions (session_type)
    WHERE session_active = true;
