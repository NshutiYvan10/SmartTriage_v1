package com.smartTriage.smartTriage_server.common.enums;

/**
 * Workflow status for patient safety incident investigation and resolution.
 */
public enum IncidentStatus {
    REPORTED,
    UNDER_REVIEW,
    INVESTIGATION_STARTED,
    ROOT_CAUSE_IDENTIFIED,
    CORRECTIVE_ACTION_PLANNED,
    CORRECTIVE_ACTION_IMPLEMENTED,
    CLOSED
}
