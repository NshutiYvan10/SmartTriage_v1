package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Clinical status of a recorded chronic condition.
 *
 * <p>Drives the patient-profile display + which conditions feed the
 * safety engine. A {@code RESOLVED} condition stays on the audit
 * trail but no longer triggers renal / teratogen / dose-adjust
 * checks — same pattern as {@code AllergyVerificationStatus.REFUTED}
 * for allergies.
 *
 * <ul>
 *   <li>{@link #ACTIVE} — currently in treatment or untreated and
 *       symptomatic. Default for newly recorded conditions.</li>
 *   <li>{@link #CONTROLLED} — diagnosed and well-managed (BP on
 *       target on antihypertensives, T2DM with HbA1c &lt; 7%).
 *       Still feeds the safety engine — the underlying physiology
 *       is unchanged.</li>
 *   <li>{@link #IN_REMISSION} — disease quiescent (cancer in
 *       remission, IBD between flares). Excluded from the active
 *       feeds; still surfaced on the chart so the doctor sees
 *       relevant history.</li>
 *   <li>{@link #RESOLVED} — fully resolved (acute event that's
 *       healed). Excluded from active feeds. Kept for audit.</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ChronicConditionStatus {

    ACTIVE("Active", "Currently in treatment or symptomatic"),
    CONTROLLED("Controlled", "Diagnosed and well-managed on therapy"),
    IN_REMISSION("In remission", "Disease quiescent — no active treatment needed"),
    RESOLVED("Resolved", "Fully resolved — kept for history only");

    private final String label;
    private final String description;

    /**
     * Does this status mean the condition should still drive
     * safety-check gating (renal, teratogen, etc.)? ACTIVE and
     * CONTROLLED → yes (the physiology is the same); IN_REMISSION
     * and RESOLVED → no.
     */
    public boolean affectsSafetyChecks() {
        return this == ACTIVE || this == CONTROLLED;
    }
}
