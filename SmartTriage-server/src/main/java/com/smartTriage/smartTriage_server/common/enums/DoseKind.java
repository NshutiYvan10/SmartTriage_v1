package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What kind of event a {@code MedicationDose} row records
 * (Medication Management, V67).
 *
 * <p>For ONE_TIME and SCHEDULED orders each row is a discrete dose.
 * PRN administrations are recorded as {@link #PRN_DOSE} at the moment
 * the nurse gives them. CONTINUOUS orders use the three INFUSION_*
 * kinds as an event log: start (with rate), each rate change, and the
 * stop — together they reconstruct the full infusion timeline.
 */
@Getter
@RequiredArgsConstructor
public enum DoseKind {

    ONE_TIME_DOSE("One-time dose"),
    SCHEDULED_DOSE("Scheduled dose"),
    PRN_DOSE("PRN dose"),
    INFUSION_START("Infusion started"),
    INFUSION_RATE_CHANGE("Infusion rate changed"),
    INFUSION_STOP("Infusion stopped");

    private final String label;
}
