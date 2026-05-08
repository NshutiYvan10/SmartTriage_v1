-- V49 — Lab two-step verification (Phase 2)
--
-- Adds the AWAITING_VERIFICATION status between PROCESSING and
-- RESULTED. When two-step verification is enabled at the hospital
-- AND the result is high-risk (critical value, specimen-quality
-- concern), the order parks in AWAITING_VERIFICATION so a
-- HEAD_LAB_TECHNICIAN must release it before the doctor sees it.
--
-- Built-in guardrails so verification can never block patient care:
--   1. Hospital-level toggle (off by default — small district sites
--      with a single tech keep self-verify behaviour).
--   2. Per-priority auto-release timeout — STAT 5 min, URGENT 15 min,
--      ROUTINE 60 min. Background scheduler flips expired rows to
--      RESULTED with verification_auto_released=true.
--   3. Junior emergency-override button — releases immediately with
--      a logged reason. The audit columns capture both paths.
--
-- The Phase 1 self-verify path is preserved untouched: when
-- verification is disabled, results go straight to RESULTED and
-- entered_by_name == verified_by_name as before.

-- Extend the status CHECK constraint to allow AWAITING_VERIFICATION.
ALTER TABLE lab_orders DROP CONSTRAINT lab_order_status_chk;
ALTER TABLE lab_orders ADD CONSTRAINT lab_order_status_chk
    CHECK (status IN ('ORDERED','SPECIMEN_COLLECTED','RECEIVED_BY_LAB',
                      'PROCESSING','AWAITING_VERIFICATION',
                      'RESULTED','REJECTED','CANCELLED'));

-- Did this result go through the verification gate?
ALTER TABLE lab_orders ADD COLUMN verification_required BOOLEAN NOT NULL DEFAULT FALSE;

-- When does the AWAITING_VERIFICATION row auto-release if no senior
-- has clicked Verify? Computed at the time of result entry from
-- priority + hospital settings; null when verification is not required.
ALTER TABLE lab_orders ADD COLUMN verification_timeout_at TIMESTAMP WITH TIME ZONE;

-- True if the timeout fired before a senior verified.
ALTER TABLE lab_orders ADD COLUMN verification_auto_released BOOLEAN NOT NULL DEFAULT FALSE;

-- Junior-tech emergency override path. When the senior is
-- unreachable but the result is time-critical, the junior can
-- self-release with a logged reason.
ALTER TABLE lab_orders ADD COLUMN verification_override BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lab_orders ADD COLUMN verification_override_reason VARCHAR(500);
ALTER TABLE lab_orders ADD COLUMN verification_override_by_name VARCHAR(255);
ALTER TABLE lab_orders ADD COLUMN verification_override_at TIMESTAMP WITH TIME ZONE;

-- Senior tech rejected the result and bounced it back to the junior
-- (typo, suspect specimen, etc.). Stored as a note + count so the
-- audit can show "rejected once, re-entered, then released".
ALTER TABLE lab_orders ADD COLUMN verification_rejection_count INT NOT NULL DEFAULT 0;
ALTER TABLE lab_orders ADD COLUMN verification_rejection_reason VARCHAR(500);
ALTER TABLE lab_orders ADD COLUMN verification_rejected_by_name VARCHAR(255);
ALTER TABLE lab_orders ADD COLUMN verification_rejected_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_lab_order_verification_pending
    ON lab_orders(status, verification_timeout_at)
    WHERE status = 'AWAITING_VERIFICATION' AND is_active = true;

-- Hospital-level toggle. Default OFF so existing pilot sites keep
-- today's behaviour. A SUPER_ADMIN flips it on for hospitals that
-- have at least one HEAD_LAB_TECHNICIAN on staff.
ALTER TABLE hospitals ADD COLUMN two_step_verification_enabled BOOLEAN NOT NULL DEFAULT FALSE;
