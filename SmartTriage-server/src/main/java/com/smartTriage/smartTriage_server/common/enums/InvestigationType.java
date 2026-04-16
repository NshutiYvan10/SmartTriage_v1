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
}
