package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Status of a medication administration entry.
 */
@Getter
@RequiredArgsConstructor
public enum MedicationStatus {

    /**
     * V67 — high-alert order awaiting charge-nurse approval before it
     * becomes administrable. Only typed orders for formulary
     * high-alert drugs enter this state; an emergency override with
     * documented justification skips it.
     */
    PENDING_APPROVAL("Awaiting approval"),
    PRESCRIBED("Prescribed"),
    ADMINISTERED("Administered"),
    HELD("Held — not given"),
    REFUSED("Refused by Patient"),
    CANCELLED("Cancelled"),
    /**
     * V67 — recurring/continuous order that reached its planned end
     * (duration elapsed or max doses given). Distinct from CANCELLED
     * (withdrawn early) and DISCONTINUED (doctor stopped it).
     */
    COMPLETED("Completed"),
    /** V67 — actively stopped by a clinician, with a documented reason. */
    DISCONTINUED("Discontinued");

    private final String description;

    /**
     * True when the order is still "live" for the dose workflow —
     * doses may be generated, given, or recorded against it.
     * PRESCRIBED is live for every type; ONE_TIME orders leave the
     * live set by transitioning to ADMINISTERED.
     */
    public boolean isLiveForDosing() {
        return this == PRESCRIBED;
    }
}
