package com.smartTriage.smartTriage_server.module.medsafety.engine;

import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;

import java.util.List;

/**
 * Immutable result of a medication safety validation run.
 *
 * @param allergyCheckResult        outcome of allergy cross-reactivity check
 * @param doseCheckResult           outcome of dose range validation
 * @param interactionCheckResult    outcome of drug-drug interaction screening
 * @param duplicateCheckResult      outcome of duplicate therapy detection
 * @param overallSafe               true only if ALL checks passed
 * @param warnings                  non-blocking warnings (HIGH severity)
 * @param blockers                  blocking issues that prevent administration (CRITICAL severity)
 * @param allergyMatchSeverity      structured severity of the matched allergy
 *                                  (null when no allergy match, or when only the
 *                                  legacy free-text fallback matched without a
 *                                  recorded severity)
 * @param allergyMatchedAllergen    display name of the patient allergen that
 *                                  triggered the match (e.g. "penicillin")
 * @param allergyReaction           reaction text from the structured allergy
 *                                  record, if any — surfaced on the override
 *                                  dialog so the prescriber sees what happened
 *                                  to the patient last time
 */
public record MedicationSafetyResult(
        CheckResult allergyCheckResult,
        CheckResult doseCheckResult,
        CheckResult interactionCheckResult,
        CheckResult duplicateCheckResult,
        boolean overallSafe,
        List<String> warnings,
        List<String> blockers,
        AllergySeverity allergyMatchSeverity,
        String allergyMatchedAllergen,
        String allergyReaction
) {

    /**
     * Compatibility constructor matching the pre-Workflow-2 shape so
     * older callers and tests that built the result without allergy
     * metadata still compile. New callers should use the canonical
     * constructor with the {@code allergyMatch*} fields populated.
     */
    public MedicationSafetyResult(
            CheckResult allergyCheckResult,
            CheckResult doseCheckResult,
            CheckResult interactionCheckResult,
            CheckResult duplicateCheckResult,
            boolean overallSafe,
            List<String> warnings,
            List<String> blockers) {
        this(allergyCheckResult, doseCheckResult, interactionCheckResult,
                duplicateCheckResult, overallSafe, warnings, blockers,
                null, null, null);
    }

    /**
     * Individual check result.
     *
     * @param passed  whether the check passed
     * @param message description of the issue (null if passed)
     * @param severity NORMAL, HIGH, or CRITICAL
     */
    public record CheckResult(
            boolean passed,
            String message,
            Severity severity
    ) {
        public static CheckResult ok() {
            return new CheckResult(true, null, Severity.NORMAL);
        }

        public static CheckResult warning(String message) {
            return new CheckResult(false, message, Severity.HIGH);
        }

        public static CheckResult critical(String message) {
            return new CheckResult(false, message, Severity.CRITICAL);
        }
    }

    public enum Severity {
        NORMAL,
        HIGH,
        CRITICAL
    }

    public enum DoseStatus {
        UNDERDOSE,
        NORMAL,
        OVERDOSE,
        CRITICAL_OVERDOSE
    }
}
