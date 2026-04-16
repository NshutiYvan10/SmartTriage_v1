package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Status of a medication administration entry.
 */
@Getter
@RequiredArgsConstructor
public enum MedicationStatus {

    PRESCRIBED("Prescribed"),
    ADMINISTERED("Administered"),
    HELD("Held — not given"),
    REFUSED("Refused by Patient"),
    CANCELLED("Cancelled");

    private final String description;
}
