package com.smartTriage.smartTriage_server.common.enums;

/**
 * Sepsis status classification following Rwanda MoH sepsis management guidelines
 * and the Surviving Sepsis Campaign (adapted for resource-limited settings).
 */
public enum SepsisStatus {

    NO_SEPSIS,
    SIRS_POSITIVE,       // Systemic Inflammatory Response Syndrome criteria met
    SEPSIS_SUSPECTED,    // SIRS + suspected infection
    SEVERE_SEPSIS,       // Sepsis + organ dysfunction
    SEPTIC_SHOCK         // Sepsis + persistent hypotension despite fluids
}
