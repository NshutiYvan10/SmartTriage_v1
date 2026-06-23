-- V93: Phase 2 of cross-hospital patient identity — consent-gated DEEP-record sharing
-- + break-the-glass emergency override.
--
-- Phase 1 (V92) shares identity + a minimal safety summary. Phase 2 opens the DEEP clinical
-- record across SmartTriage hospitals ONLY with the patient's explicit opt-in consent
-- (data_sharing_consents), with an emergency override (break_the_glass_events) that is
-- mandatory-reason and immutably recorded. Both are keyed on the shared PersonIdentity.

CREATE TABLE data_sharing_consents (
    id                        UUID PRIMARY KEY,
    created_at                TIMESTAMP NOT NULL,
    updated_at                TIMESTAMP,
    created_by                VARCHAR(255),
    last_modified_by          VARCHAR(255),
    is_active                 BOOLEAN NOT NULL DEFAULT TRUE,
    version                   BIGINT,

    person_identity_id        UUID NOT NULL REFERENCES person_identities(id),
    status                    VARCHAR(20) NOT NULL,   -- GRANTED | DENIED | WITHDRAWN
    scope                     VARCHAR(30) NOT NULL,   -- FULL_RECORD

    -- Who the consent was obtained from (mirrors informed_consents grantor block)
    consent_grantor           VARCHAR(40) NOT NULL,   -- PATIENT | PARENT_OR_GUARDIAN | NEXT_OF_KIN | ...
    grantor_name              VARCHAR(255),
    grantor_relationship      VARCHAR(255),

    -- Authenticated clinician/registrar who recorded it (snapshot, never client-supplied)
    obtained_by_user_id       UUID,
    obtained_by_name          VARCHAR(255) NOT NULL,
    obtained_by_role          VARCHAR(30),
    obtained_by_license_number VARCHAR(50),
    obtained_at               TIMESTAMP NOT NULL,

    -- Withdrawal block
    withdrawn_by_user_id      UUID,
    withdrawn_by_name         VARCHAR(255),
    withdrawn_at              TIMESTAMP,
    withdrawal_reason         TEXT,

    notes                     TEXT
);

CREATE INDEX idx_dsc_person_identity ON data_sharing_consents (person_identity_id);
CREATE INDEX idx_dsc_status ON data_sharing_consents (status);

-- At most one live GRANTED consent per person at a time (the "effective consent").
-- DB-enforced; the service supersedes-on-regrant and catches this as a concurrency backstop.
CREATE UNIQUE INDEX uq_dsc_one_active_grant
    ON data_sharing_consents (person_identity_id)
    WHERE status = 'GRANTED' AND is_active = TRUE;

CREATE TABLE break_the_glass_events (
    id                   UUID PRIMARY KEY,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP,
    created_by           VARCHAR(255),
    last_modified_by     VARCHAR(255),
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    version              BIGINT,

    person_identity_id   UUID NOT NULL REFERENCES person_identities(id),
    actor_user_id        UUID,
    actor_name           VARCHAR(255) NOT NULL,
    actor_role           VARCHAR(30),
    actor_hospital_id    UUID,
    reason               TEXT NOT NULL,
    prior_consent_state  VARCHAR(20),   -- NONE | DENIED | WITHDRAWN (what consent looked like at override time)
    accessed_at          TIMESTAMP NOT NULL
);

CREATE INDEX idx_btg_person_identity ON break_the_glass_events (person_identity_id);
CREATE INDEX idx_btg_actor ON break_the_glass_events (actor_user_id);
