package com.smartTriage.smartTriage_server.common.enums;

/**
 * Status progression for inter-hospital referrals.
 */
public enum ReferralStatus {
    INITIATED,
    RECEIVING_FACILITY_CONTACTED,
    ACCEPTED,
    DECLINED,
    PATIENT_STABILIZED,
    IN_TRANSIT,
    RECEIVED_AT_DESTINATION,
    COMPLETED,
    CANCELLED
}
