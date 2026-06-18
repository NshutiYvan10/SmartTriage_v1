-- V86: Informed-consent records (clinical documentation gap #5).
--
-- Previously consent existed only as an unused enum + a dead referrals.consent
-- column — there was no way to document that informed consent was obtained for a
-- procedure, transfusion, anaesthesia, etc. This adds a structured, attributable
-- consent record: what intervention, what was disclosed (risks/benefits/
-- alternatives), whether questions were answered and an interpreter used, WHO
-- consented (patient or named proxy + relationship), and the AUTHENTICATED
-- clinician who obtained it (id/name/role/license snapshot). Consent may be
-- GIVEN, REFUSED, or later WITHDRAWN (original obtaining record preserved).

CREATE TABLE informed_consents (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP,
    created_by          VARCHAR(255),
    last_modified_by    VARCHAR(255),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    version             BIGINT,

    visit_id                UUID NOT NULL REFERENCES visits(id),
    consent_type            VARCHAR(30) NOT NULL,
    procedure_name          VARCHAR(255) NOT NULL,
    description             TEXT,

    -- Disclosure
    risks_explained         TEXT,
    benefits_explained      TEXT,
    alternatives_explained  TEXT,
    questions_answered      BOOLEAN NOT NULL DEFAULT FALSE,
    interpreter_used        BOOLEAN NOT NULL DEFAULT FALSE,
    interpreter_name        VARCHAR(255),
    language                VARCHAR(255),

    -- Who consented
    consent_grantor         VARCHAR(40) NOT NULL,
    grantor_name            VARCHAR(255),
    grantor_relationship    VARCHAR(255),
    witness_name            VARCHAR(255),

    -- Status + authenticated obtaining clinician
    status                  VARCHAR(20) NOT NULL,
    obtained_by_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    obtained_by_name        VARCHAR(255) NOT NULL,
    obtained_by_role        VARCHAR(255),
    obtained_by_license_number VARCHAR(50),
    obtained_at             TIMESTAMP NOT NULL,

    -- Withdrawal
    withdrawn_by_user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    withdrawn_by_name       VARCHAR(255),
    withdrawn_at            TIMESTAMP,
    withdrawal_reason       TEXT,

    notes                   TEXT
);

CREATE INDEX idx_consent_visit ON informed_consents(visit_id);
CREATE INDEX idx_consent_type ON informed_consents(consent_type);
CREATE INDEX idx_consent_status ON informed_consents(status);
CREATE INDEX idx_consent_obtained_by ON informed_consents(obtained_by_user_id);
CREATE INDEX idx_consent_active ON informed_consents(is_active);
