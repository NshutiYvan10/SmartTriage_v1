package com.smartTriage.smartTriage_server.common.enums;

/**
 * Type of deterioration pattern detected by the AI monitoring engine.
 */
public enum DeteriorationPattern {

    /** Single vital breached a critical threshold */
    SINGLE_VITAL_CRITICAL,

    /** Multiple vitals trending in a dangerous direction simultaneously */
    MULTI_VITAL_TREND,

    /** Rapid decline in one or more parameters over a short window */
    RAPID_DECLINE,

    /** Sustained abnormality over monitoring window (not transient) */
    SUSTAINED_ABNORMALITY,

    /** SpO2 dropping below triage form override threshold (≤92%) */
    SPO2_OVERRIDE,

    /** Heart rate variability pattern suggestive of sepsis */
    SEPSIS_PATTERN,

    /** Respiratory pattern suggestive of impending respiratory failure */
    RESPIRATORY_FAILURE_PATTERN,

    /** Hemodynamic instability pattern (BP + HR combined) */
    HEMODYNAMIC_INSTABILITY,

    /** Device disconnection — patient status unknown (safety alert) */
    DEVICE_DISCONNECTED,

    /** No deterioration detected */
    NONE
}
