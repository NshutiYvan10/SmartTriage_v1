package com.smartTriage.smartTriage_server.common.enums;

/**
 * Lifecycle of an informed-consent record.
 *   GIVEN    — consent was obtained and documented.
 *   REFUSED  — the patient/grantor declined; documented for the legal record.
 *   WITHDRAWN — previously-given consent was later revoked.
 */
public enum ConsentStatus {
    GIVEN,
    REFUSED,
    WITHDRAWN
}
