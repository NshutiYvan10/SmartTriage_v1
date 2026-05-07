-- V42: Staff leave / absence tracking
--
-- Why: the shift planner needs structured visibility into "who can't be
-- scheduled, when, why" — without it, the Charge Nurse only finds out a
-- nurse is on maternity leave when nobody shows up at 07:00. Today the
-- system has no concept of leave at all; the only way to mark someone
-- unavailable is to simply not assign them, which leaves no audit trail
-- and no signal to the swap workflow.
--
-- Scope:
--   * In-scope: leave windows that affect shift scheduling at this hospital.
--   * Out-of-scope: payroll, accrual, balance tracking. SmartTriage is a
--     clinical-safety system, not an HR product. The optional
--     external_reference column lets a future HRIS sync attach an external
--     record id without us re-implementing the HR domain.
--
-- Lifecycle (LeaveStatus):
--   REQUESTED → APPROVED   — blocks scheduling
--   REQUESTED → REJECTED   — inert
--   APPROVED  → CANCELLED  — staff member or CN withdraws
--   REQUESTED → CANCELLED  — staff member withdraws before review
--
-- Retroactive sick leave: a CN may create a row already in APPROVED
-- state covering past dates ("nurse called in sick this morning"). This
-- is fine; the approved_by_id captures who made the call.

CREATE TABLE staff_leaves (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- audit
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT,

    user_id     UUID NOT NULL REFERENCES users(id)     ON DELETE RESTRICT,
    hospital_id UUID NOT NULL REFERENCES hospitals(id) ON DELETE RESTRICT,

    leave_type   VARCHAR(20) NOT NULL,                 -- LeaveType enum
    leave_status VARCHAR(15) NOT NULL DEFAULT 'REQUESTED',

    starts_on DATE NOT NULL,                           -- inclusive
    ends_on   DATE NOT NULL,                           -- inclusive

    reason TEXT,                                        -- required for SICK / COMPASSIONATE in app layer

    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    requested_by_id UUID REFERENCES users(id) ON DELETE RESTRICT,

    approved_at      TIMESTAMP WITH TIME ZONE,
    approved_by_id   UUID REFERENCES users(id) ON DELETE RESTRICT,

    rejected_at      TIMESTAMP WITH TIME ZONE,
    rejected_by_id   UUID REFERENCES users(id) ON DELETE RESTRICT,
    rejection_reason TEXT,

    cancelled_at     TIMESTAMP WITH TIME ZONE,
    cancelled_by_id  UUID REFERENCES users(id) ON DELETE RESTRICT,

    -- Hook for a future HR-system bridge — opaque to SmartTriage.
    external_reference VARCHAR(120),

    CONSTRAINT chk_staff_leave_window CHECK (ends_on >= starts_on),
    CONSTRAINT chk_staff_leave_status CHECK (
        leave_status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    CONSTRAINT chk_staff_leave_type CHECK (
        leave_type IN ('ANNUAL', 'SICK', 'MATERNITY', 'BEREAVEMENT',
                       'COMPASSIONATE', 'STUDY', 'OTHER')
    )
);

-- Hot path: "is this user on approved leave on this date?" — used by the
-- planner before letting a CN drop a tile on the calendar.
CREATE INDEX idx_staff_leave_user_window
    ON staff_leaves(user_id, starts_on, ends_on)
    WHERE leave_status = 'APPROVED' AND is_active = TRUE;

-- Coverage map: "everyone away at this hospital between A and B".
CREATE INDEX idx_staff_leave_hospital_window
    ON staff_leaves(hospital_id, starts_on, ends_on)
    WHERE leave_status = 'APPROVED' AND is_active = TRUE;

-- Approval queue lookup.
CREATE INDEX idx_staff_leave_pending
    ON staff_leaves(hospital_id, requested_at)
    WHERE leave_status = 'REQUESTED' AND is_active = TRUE;

COMMENT ON TABLE staff_leaves IS
    'Structured staff absence records consumed by the SmartTriage shift '
    'planner. Not an HR system; payroll / balance tracking is out of scope.';
