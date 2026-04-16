package com.smartTriage.smartTriage_server.module.medsafety.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from the medication safety validation engine.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicationSafetyResponse {

    private boolean allergyCheckPassed;
    private String allergyWarning;

    private boolean doseCheckPassed;
    private String doseWarning;

    private boolean interactionCheckPassed;
    private String interactionWarning;

    private boolean duplicateTherapyCheckPassed;
    private String duplicateWarning;

    private boolean overallSafe;

    /** Non-blocking warnings (HIGH severity) */
    private List<String> warnings;

    /** Blocking issues requiring override (CRITICAL severity) */
    private List<String> blockers;

    /** Severity level: NORMAL, HIGH, or CRITICAL */
    private String severity;
}
