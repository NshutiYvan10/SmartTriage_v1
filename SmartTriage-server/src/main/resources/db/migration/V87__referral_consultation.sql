-- V87: Referral / consultation records (clinical documentation gap #5).
--
-- Previously CONSULTATION_NOTE was a bare enum and there was NO referral entity
-- at all (the V13 referrals.consent column was orphaned) — inter-service handoffs
-- (ED -> Cardiology / ICU / external facility) lived only as prose, if anywhere.
-- This adds a structured request + structured response: specialty, urgency,
-- reason, specific clinical question, target facility, status lifecycle, the
-- AUTHENTICATED requester, and the AUTHENTICATED consultant's reply.

CREATE TABLE referrals (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP,
    created_by          VARCHAR(255),
    last_modified_by    VARCHAR(255),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    version             BIGINT,

    visit_id              UUID NOT NULL REFERENCES visits(id),
    referral_type         VARCHAR(30) NOT NULL,
    specialty             VARCHAR(255) NOT NULL,
    urgency               VARCHAR(20) NOT NULL,
    reason_for_referral   TEXT NOT NULL,
    clinical_question     TEXT,
    target_facility       VARCHAR(255),
    status                VARCHAR(20) NOT NULL,

    -- Requester (authenticated)
    requested_by_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    requested_by_name     VARCHAR(255) NOT NULL,
    requested_by_role     VARCHAR(255),
    requested_at          TIMESTAMP NOT NULL,

    -- Response (authenticated consultant)
    responded_by_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    responded_by_name     VARCHAR(255),
    responded_by_role     VARCHAR(255),
    responded_at          TIMESTAMP,
    response_notes        TEXT,
    decline_reason        TEXT,

    notes                 TEXT
);

CREATE INDEX idx_referral_visit ON referrals(visit_id);
CREATE INDEX idx_referral_status ON referrals(status);
CREATE INDEX idx_referral_specialty ON referrals(specialty);
CREATE INDEX idx_referral_requested_by ON referrals(requested_by_user_id);
CREATE INDEX idx_referral_active ON referrals(is_active);
