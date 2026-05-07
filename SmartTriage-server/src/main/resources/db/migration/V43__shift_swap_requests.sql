-- V43: Shift swap workflow
--
-- Why: today, when a nurse needs to trade shifts with a colleague, the
-- exchange happens informally — over WhatsApp, on paper, or by simply
-- showing up and hoping the CN agrees. There is no signal to the system,
-- which means:
--   * the shift board renders an inaccurate picture of who is on the floor;
--   * coverage gaps surface only when an alert fails to find a recipient;
--   * the CN cannot verify that the swap preserves competence requirements
--     (e.g. RESUS needs at least one ALS-credentialed nurse on shift).
--
-- A formal request flow lets us keep the board correct, route a Tier 1
-- alert to the right phone, and gate the swap on CN approval so a clinical
-- competence check happens before the assignment moves.
--
-- State machine (com.smartTriage…enums.SwapStatus):
--
--   REQUESTED ─→ PENDING_PARTNER_ACCEPT
--   PENDING_PARTNER_ACCEPT ─→ {PENDING_CHARGE_APPROVAL | REJECTED | CANCELLED}
--   PENDING_CHARGE_APPROVAL ─→ {APPROVED | REJECTED | CANCELLED}
--   APPROVED / REJECTED / CANCELLED are terminal.
--
-- The actual swap (rewiring the two ShiftAssignment rows) is performed in
-- a SINGLE @Transactional method on the APPROVED transition, after a
-- final coverage / competence re-check. If the re-check fails the
-- transition is rejected with REJECTED + rejection_reason.

CREATE TABLE shift_swap_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- audit
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255),
    last_modified_by VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT,

    hospital_id UUID NOT NULL REFERENCES hospitals(id) ON DELETE RESTRICT,

    -- The two assignments being traded. Both must belong to the same
    -- hospital; enforced at the application layer because Postgres CHECK
    -- can't look across rows.
    requester_assignment_id UUID NOT NULL
        REFERENCES shift_assignments(id) ON DELETE RESTRICT,
    partner_assignment_id   UUID NOT NULL
        REFERENCES shift_assignments(id) ON DELETE RESTRICT,

    -- Snapshot of who initiated and who is the named partner. Cheap
    -- duplication of shift_assignments.user_id, but lets us answer
    -- "show me my swap requests" without joining the assignments table.
    requester_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    partner_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',

    request_reason TEXT,                                -- proposer's note ("daughter's wedding")

    partner_responded_at TIMESTAMP WITH TIME ZONE,
    partner_response_note TEXT,

    charge_responded_at TIMESTAMP WITH TIME ZONE,
    charge_responder_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    charge_response_note TEXT,

    cancelled_at    TIMESTAMP WITH TIME ZONE,
    cancelled_by_id UUID REFERENCES users(id) ON DELETE RESTRICT,

    rejection_reason TEXT,

    CONSTRAINT chk_swap_distinct_assignments CHECK (
        requester_assignment_id <> partner_assignment_id
    ),
    CONSTRAINT chk_swap_distinct_users CHECK (
        requester_user_id <> partner_user_id
    ),
    CONSTRAINT chk_swap_status CHECK (status IN (
        'REQUESTED',
        'PENDING_PARTNER_ACCEPT',
        'PENDING_CHARGE_APPROVAL',
        'APPROVED',
        'REJECTED',
        'CANCELLED'
    ))
);

-- "My open swap requests" — both as requester and as named partner.
CREATE INDEX idx_swap_requester_open
    ON shift_swap_requests(requester_user_id, status)
    WHERE status IN ('REQUESTED', 'PENDING_PARTNER_ACCEPT', 'PENDING_CHARGE_APPROVAL');

CREATE INDEX idx_swap_partner_open
    ON shift_swap_requests(partner_user_id, status)
    WHERE status IN ('REQUESTED', 'PENDING_PARTNER_ACCEPT', 'PENDING_CHARGE_APPROVAL');

-- CN approval queue.
CREATE INDEX idx_swap_charge_queue
    ON shift_swap_requests(hospital_id, created_at)
    WHERE status = 'PENDING_CHARGE_APPROVAL';

-- One open swap per assignment side at a time — prevents double-booking
-- the same row in two competing swaps.
CREATE UNIQUE INDEX uk_swap_requester_open_assignment
    ON shift_swap_requests(requester_assignment_id)
    WHERE status IN ('REQUESTED', 'PENDING_PARTNER_ACCEPT', 'PENDING_CHARGE_APPROVAL');

CREATE UNIQUE INDEX uk_swap_partner_open_assignment
    ON shift_swap_requests(partner_assignment_id)
    WHERE status IN ('REQUESTED', 'PENDING_PARTNER_ACCEPT', 'PENDING_CHARGE_APPROVAL');

COMMENT ON TABLE shift_swap_requests IS
    'Peer-to-peer shift trade requests. Approved swaps mutate the two '
    'underlying ShiftAssignment rows atomically.';
