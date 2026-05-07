package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Lifecycle state of a staff leave request.
 *
 * <p>Only {@link #APPROVED} leave blocks the shift planner — REQUESTED rows
 * are advisory (the staff member intends to be away) and CANCELLED /
 * REJECTED rows are inert. SICK leave is allowed to be retro-approved
 * (created already in APPROVED state by a Charge Nurse on the staff
 * member's behalf).
 */
@Getter
@RequiredArgsConstructor
public enum LeaveStatus {
    REQUESTED("Requested",  "Submitted, awaiting CN / HA approval"),
    APPROVED ("Approved",   "Confirmed off the floor — blocks scheduling"),
    REJECTED ("Rejected",   "Not approved — does not block scheduling"),
    CANCELLED("Cancelled",  "Withdrawn before or after approval");

    private final String label;
    private final String description;
}
