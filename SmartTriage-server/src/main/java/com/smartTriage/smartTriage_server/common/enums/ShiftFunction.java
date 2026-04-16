package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Operational function a staff member performs during a specific shift.
 *
 * This is NOT a permanent role — it changes every shift.
 * A Senior Nurse (designation) might be CHARGE_NURSE this morning
 * and ZONE_NURSE this afternoon.
 *
 * The alert system uses ShiftFunction to determine routing:
 * - PRIMARY_DOCTOR → receives Tier 1 zone alerts
 * - CHARGE_NURSE → receives all Tier 1 alerts + manages shift board
 * - SUPERVISING_DOCTOR → receives Tier 2 escalations
 * - TRIAGE_NURSE → assigned to triage station
 * - ZONE_NURSE → assigned to a treatment zone
 * - RESIDENT → works under supervising doctor
 */
@Getter
@RequiredArgsConstructor
public enum ShiftFunction {

    CHARGE_NURSE("Charge Nurse", "Manages the ED floor and shift assignments"),
    TRIAGE_NURSE("Triage Nurse", "Assigned to the triage station"),
    ZONE_NURSE("Zone Nurse", "Assigned to a specific treatment zone"),
    PRIMARY_DOCTOR("Primary Doctor", "Primary doctor for a zone — receives Tier 1 alerts"),
    SUPERVISING_DOCTOR("Supervising Doctor", "Senior doctor overseeing zone — receives Tier 2 escalations"),
    RESIDENT("Resident", "Doctor-in-training working under supervision");

    private final String label;
    private final String description;
}
