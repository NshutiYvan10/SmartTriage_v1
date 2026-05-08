package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Pre-hospital intervention categories. Mirrors the CHECK constraint
 * on {@code ems_interventions.type}.
 */
@Getter
@RequiredArgsConstructor
public enum EmsInterventionType {
    OXYGEN("Oxygen / ventilation"),
    IV_ACCESS("IV / IO access"),
    FLUID("IV fluid"),
    MEDICATION("Medication"),
    DEFIBRILLATION("Defibrillation / cardioversion"),
    AIRWAY("Advanced airway"),
    IMMOBILISATION("C-spine / pelvic binder"),
    SPLINTING("Splinting / fracture care"),
    TOURNIQUET("Tourniquet / haemostasis"),
    CPR("CPR / chest compressions"),
    OTHER("Other");

    private final String description;
}
