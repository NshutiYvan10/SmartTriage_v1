package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Lifecycle status of a physical bed/cubicle/bay in the ED.
 *
 * AVAILABLE     — empty, cleaned, ready to receive a patient.
 * OCCUPIED      — a patient is currently placed in this bed.
 *                 Set automatically when BedService.placePatient() succeeds.
 * CLEANING      — patient has left; bed must be cleaned/sanitised before the
 *                 next patient is placed. This transition is mandatory to
 *                 prevent the previous patient's tail-end vitals from landing
 *                 on the next patient's chart, and to meet infection-control
 *                 policy (especially relevant after isolation patients).
 * OUT_OF_SERVICE — bed is broken, under maintenance, or temporarily removed
 *                 from the roster. Cannot receive patients until an admin
 *                 returns it to AVAILABLE.
 *
 * Allowed transitions (enforced by BedService):
 *   AVAILABLE     → OCCUPIED       (placePatient)
 *   OCCUPIED      → CLEANING       (dischargePatient, transferPatient)
 *   CLEANING      → AVAILABLE      (markCleaned)
 *   AVAILABLE     → OUT_OF_SERVICE (markOutOfService — admin)
 *   OUT_OF_SERVICE → AVAILABLE     (markAvailable — admin)
 *   CLEANING      → OUT_OF_SERVICE (markOutOfService — admin)
 */
@Getter
@RequiredArgsConstructor
public enum BedStatus {
    AVAILABLE("Available"),
    OCCUPIED("Occupied"),
    CLEANING("Cleaning"),
    OUT_OF_SERVICE("Out of Service");

    private final String label;
}
