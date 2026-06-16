package com.smartTriage.smartTriage_server.common.enums;

/**
 * Hypoglycemia severity bands (replaces the previous free-text severity string).
 *
 * Adult/child thresholds align to ADA / WHO blood-glucose cut-offs (mmol/L):
 *   NORMAL    >= 3.9   (>= 70 mg/dL)
 *   MILD      < 3.9    (< 70 mg/dL) — Level-1 "alert" value
 *   MODERATE  < 3.0    (< 54 mg/dL) — Level-2 clinically significant
 *   SEVERE    < 2.2    (< 40 mg/dL) OR any hypoglycemia with neuroglycopenia
 *                                    (altered consciousness / convulsions / coma)
 *
 * Neonatal (< 28 days) uses the neonatal treatment threshold < 2.6 mmol/L
 * (< 47 mg/dL); see HypoglycemiaEnforcementEngine.
 *
 * PENDING_CHECK = a check is required but no glucose value is yet available.
 * NONE          = no check required and no glucose interpreted.
 */
public enum HypoglycemiaSeverity {
    NONE,
    PENDING_CHECK,
    NORMAL,
    MILD,
    MODERATE,
    SEVERE;

    /** True for any band that represents actual hypoglycemia requiring action. */
    public boolean isHypoglycemic() {
        return this == MILD || this == MODERATE || this == SEVERE;
    }

    /** Bands that warrant a CRITICAL (vs HIGH) alert + immediate IV/owned escalation. */
    public boolean isCritical() {
        return this == MODERATE || this == SEVERE;
    }
}
