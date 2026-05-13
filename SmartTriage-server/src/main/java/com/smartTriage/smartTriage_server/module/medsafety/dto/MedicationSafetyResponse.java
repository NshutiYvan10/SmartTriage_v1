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

    // ────────── ALLERGY MATCH DETAIL (Workflow 2) ──────────
    // Populated when checkAllergies matched a structured PatientAllergy
    // (or the legacy free-text fallback fired). Lets the prescribe-time
    // safety dialog render the right flavour (soft warning for MILD,
    // hard stop for SEVERE/ANAPHYLAXIS) and show the prior reaction.

    /** Structured severity of the matched allergy, e.g. "ANAPHYLAXIS". Null when no match. */
    private String allergyMatchSeverity;

    /** Display name of the matched patient allergen, e.g. "penicillin". */
    private String allergyMatchedAllergen;

    /** Reaction text from the structured allergy record, if any. */
    private String allergyReaction;
}
