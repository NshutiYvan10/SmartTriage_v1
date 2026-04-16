package com.smartTriage.smartTriage_server.common.enums;

/**
 * Status lifecycle for an ICU escalation request.
 * Tracks the escalation from initial request through to ICU transfer or cancellation.
 */
public enum IcuEscalationStatus {

    REQUESTED,
    ICU_NOTIFIED,
    ICU_ACCEPTED,
    ICU_DECLINED,
    TRANSFERRED_TO_ICU,
    STABILIZING,
    CANCELLED
}
