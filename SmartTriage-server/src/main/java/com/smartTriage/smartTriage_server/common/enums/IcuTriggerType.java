package com.smartTriage.smartTriage_server.common.enums;

/**
 * Types of clinical triggers that warrant ICU escalation.
 * Based on standard ICU admission criteria adapted for Rwanda hospital capacity.
 */
public enum IcuTriggerType {

    HEMODYNAMIC_INSTABILITY,     // MAP < 65 or requiring vasopressors
    RESPIRATORY_FAILURE,          // SpO2 < 90% on supplemental O2, RR > 35
    DECREASED_CONSCIOUSNESS,      // GCS <= 8 or rapid decline
    SEPTIC_SHOCK,                // Sepsis with persistent hypotension
    POST_CARDIAC_ARREST,         // ROSC after cardiac arrest
    STATUS_EPILEPTICUS,          // Refractory seizures
    MASSIVE_HEMORRHAGE,          // Requiring massive transfusion protocol
    MULTI_ORGAN_DYSFUNCTION,     // >= 2 organ systems failing
    POST_OPERATIVE,              // Post-emergency surgery
    CLINICAL_JUDGEMENT           // Clinician decision
}
