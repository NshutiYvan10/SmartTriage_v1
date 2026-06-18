package com.smartTriage.smartTriage_server.common.enums;

/**
 * WHO gave (or refused) consent. For an incapacitated or minor patient, consent
 * is provided by a proxy — that proxy's identity and relationship must be on the
 * legal record.
 */
public enum ConsentGrantor {
    PATIENT,
    PARENT_OR_GUARDIAN,
    NEXT_OF_KIN,
    LEGAL_SURROGATE,
    COURT_ORDER,
    EMERGENCY_NO_CONSENT_REQUIRED
}
