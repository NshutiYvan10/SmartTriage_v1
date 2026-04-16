package com.smartTriage.smartTriage_server.common.enums;

/**
 * Severity classification for patient safety incidents.
 */
public enum IncidentSeverity {
    NEAR_MISS,          // Caught before reaching patient
    NO_HARM,            // Reached patient but no harm
    MILD_HARM,          // Required additional monitoring
    MODERATE_HARM,      // Required intervention
    SEVERE_HARM,        // Permanent harm or prolonged hospitalization
    DEATH               // Contributed to death
}
