package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Types of clinical investigations ordered in the ED.
 */
@Getter
@RequiredArgsConstructor
public enum InvestigationType {

    LABORATORY("Laboratory Test"),
    RADIOLOGY("Radiology / Imaging"),
    ECG("Electrocardiogram"),
    ULTRASOUND("Ultrasound"),
    CT_SCAN("CT Scan"),
    MRI("MRI"),
    XRAY("X-Ray"),
    BLOOD_GAS("Blood Gas Analysis"),
    URINALYSIS("Urinalysis"),
    RAPID_TEST("Rapid Diagnostic Test"),
    POINT_OF_CARE("Point-of-Care Test"),
    OTHER("Other");

    private final String description;

    /**
     * Whether an investigation of this type is routed to the laboratory — i.e. it
     * spawns a LabOrder the lab owns and drives through its own lifecycle. The single
     * source of truth for both the order-time bridge (InvestigationService) and the
     * doctor-chart UI gating (so the chart does not offer specimen/result actions for
     * an investigation the lab owns, which would desync the two records).
     */
    public boolean isLabRoutable() {
        return this == LABORATORY || this == BLOOD_GAS || this == URINALYSIS || this == RAPID_TEST;
    }
}
