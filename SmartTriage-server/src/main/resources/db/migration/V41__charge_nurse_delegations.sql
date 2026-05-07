-- V41: Acting Charge Nurse delegation
--
-- Why: in Rwandan EDs the Charge Nurse (CN) is the single point of authority
-- for shift staffing decisions. When the CN is unavailable for a finite period
-- (going home for the night before night-CN clocks in, sick leave, off-site
-- meeting), the unit cannot have an authority gap. Without a structured
-- delegation, sites work around it by either (a) leaving the badge with the
-- absent CN — which routes Tier 1 alerts to a phone nobody is holding — or
-- (b) handing the badge informally on WhatsApp, leaving no audit trail.
--
-- This table records explicit, time-bound delegation of CN authority to a
-- named acting CN. The shift-assignment authorization chain
-- (ShiftAssignmentAuthz#canAssign) honors an active row here as if the
-- delegate were the on-duty CN for the window.
--
-- Lifecycle:
--   * Created by the delegating CN (or a Hospital Admin acting on their behalf)
--     at any time before or during the absence.
--   * Active when:  is_active = true
--                AND revoked_at IS NULL
--                AND now() BETWEEN starts_at AND COALESCE(ends_at, 'infinity')
--   * Revoked early by setting revoked_at + revoked_by_id (e.g. delegating CN
--     returns ahead of schedule).
--   * Open-ended (ends_at = NULL) is allowed for "until I revoke this", but
--     the UI must surface it and a reminder fires after 24h to prompt close.
--
-- Auditing: every row is append-mostly. Soft-delete via the inherited
-- is_active flag is reserved for "this row was created in error" and must
-- be accompanied by a separate audit-trail entry.

CREATE TABLE charge_nurse_delegations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- audit
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT,

    hospital_id UUID NOT NULL
        REFERENCES hospitals(id) ON DELETE RESTRICT,

    -- The CN giving up authority for the window. Their badge stays in place;
    -- this row is purely additive — both the CN and the delegate hold
    -- canAssign authority while active.
    delegating_user_id UUID NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    -- The acting CN. Constrained to NURSE role at the application layer
    -- (Designation.SENIOR_NURSE recommended; CHARGE_NURSE designation is
    -- redundant since they would already have authority).
    delegate_user_id UUID NOT NULL
        REFERENCES users(id) ON DELETE RESTRICT,

    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at   TIMESTAMP WITH TIME ZONE,             -- NULL = open-ended

    reason TEXT NOT NULL,                            -- free text, e.g. "Off-site MoH meeting"

    revoked_at    TIMESTAMP WITH TIME ZONE,
    revoked_by_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    revocation_reason TEXT,

    -- A delegation must point forward in time within itself.
    CONSTRAINT chk_cnd_window CHECK (ends_at IS NULL OR ends_at > starts_at),

    -- Self-delegation is meaningless and likely a UI bug — block it.
    CONSTRAINT chk_cnd_distinct_users CHECK (delegating_user_id <> delegate_user_id)
);

CREATE INDEX idx_cnd_hospital_active
    ON charge_nurse_delegations(hospital_id, delegate_user_id)
    WHERE is_active = TRUE AND revoked_at IS NULL;

CREATE INDEX idx_cnd_delegating_user
    ON charge_nurse_delegations(delegating_user_id)
    WHERE is_active = TRUE;

CREATE INDEX idx_cnd_window
    ON charge_nurse_delegations(hospital_id, starts_at, ends_at)
    WHERE is_active = TRUE AND revoked_at IS NULL;

COMMENT ON TABLE charge_nurse_delegations IS
    'Time-bound delegation of Charge Nurse authority to a named acting CN. '
    'Honored by ShiftAssignmentAuthz#canAssign while active.';
