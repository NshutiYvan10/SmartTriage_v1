package com.smartTriage.smartTriage_server.common.enums;

/**
 * Status of a patient visit through the ED workflow.
 */
public enum VisitStatus {
    REGISTERED,
    AWAITING_TRIAGE,
    TRIAGED,
    AWAITING_ASSESSMENT,
    UNDER_ASSESSMENT,
    UNDER_TREATMENT,
    UNDER_OBSERVATION,
    PENDING_DISPOSITION,
    DISCHARGED,
    ADMITTED,
    TRANSFERRED,
    ICU_ADMITTED,
    LEFT_WITHOUT_BEING_SEEN,
    DECEASED
}
