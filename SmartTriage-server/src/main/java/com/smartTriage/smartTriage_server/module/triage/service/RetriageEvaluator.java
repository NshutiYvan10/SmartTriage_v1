package com.smartTriage.smartTriage_server.module.triage.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignCategory;
import com.smartTriage.smartTriage_server.module.clinicalsigns.entity.ClinicalSignStatus;

/**
 * RetriageEvaluator — pure decision class for Round 3.
 *
 * <p>Given a freshly-recorded clinical-sign event plus the patient's current
 * triage category, decides whether the system should:
 * <ul>
 *   <li>{@link AutoBump} — auto-create a new TriageRecord at the higher
 *       category. Used for the deterministic Rwandan rules: any positive
 *       Emergency Sign forces RED; any positive Pediatric Emergency Sign on
 *       a pediatric patient forces RED. No clinician judgement is required
 *       for these — the form already commits to the answer.</li>
 *   <li>{@link Suggest} — fire a {@code RETRIAGE_REQUIRED} alert and let the
 *       nurse decide. Used for mSAT VU/URG worsenings where the resulting
 *       category depends on multiple discriminators (and we don't have
 *       fresh vitals to recompute TEWS).</li>
 *   <li>{@link NoAction} — current category already covers it, sign isn't
 *       worsening, status is UNKNOWN (uncertainty, not new info), or this
 *       is a baseline event from triage bootstrapping.</li>
 * </ul>
 *
 * <p>Pure: no Spring deps, no I/O. The calling service supplies the
 * inputs, applies the decision, and handles persistence side-effects.
 *
 * <p>UNKNOWN preservation: a sign moving to UNKNOWN is treated as
 * {@link NoAction}. UNKNOWN is "we cannot assess" — it's never grounds for
 * automatic escalation, because escalation needs new positive information.
 *
 * <p>Down-bumps (IMPROVING, RESOLVED, ABSENT) are explicitly out of scope
 * for Round 3 — they're clinically more dangerous to automate and need a
 * separate design discussion.
 */
public final class RetriageEvaluator {

    private RetriageEvaluator() {}

    /** Sealed result hierarchy — every call returns exactly one of these. */
    public sealed interface RetriageDecision permits AutoBump, Suggest, NoAction {}

    /**
     * The system should immediately create a new TriageRecord at
     * {@code targetCategory} with {@code isSystemTriggered=true}.
     */
    public record AutoBump(TriageCategory targetCategory, String reason) implements RetriageDecision {}

    /**
     * The system should create a {@code RETRIAGE_REQUIRED} alert at
     * {@code severity}. The nurse will press the existing manual re-triage
     * path if they agree.
     */
    public record Suggest(AlertSeverity severity, String message) implements RetriageDecision {}

    /** No re-triage signal warranted. */
    public record NoAction() implements RetriageDecision {}

    private static final NoAction NO_ACTION = new NoAction();

    /**
     * Round 4b overload — accepts the previous-known status for this
     * sign on this visit so down-trajectory transitions
     * (PRESENT/WORSENING → ABSENT/IMPROVING) can be detected and
     * surface as MEDIUM-severity Suggest re-triages. Pass {@code null}
     * for {@code previousStatus} when there is no prior event for this
     * sign (typical for the first observation).
     */
    public static RetriageDecision evaluate(
            ClinicalSignCategory signCategory,
            ClinicalSignStatus newStatus,
            ClinicalSignStatus previousStatus,
            boolean isBaseline,
            boolean isPediatric,
            TriageCategory currentCategory,
            String signLabelForMessages
    ) {
        // Baseline events come from the triage form itself — the engine
        // already committed to a category that takes them into account.
        if (isBaseline) return NO_ACTION;

        // Up-trajectory branch (Round 3 logic, unchanged):
        if (newStatus == ClinicalSignStatus.PRESENT || newStatus == ClinicalSignStatus.WORSENING) {

            // EMERGENCY signs deterministically map to RED per Rwandan rules.
            if (signCategory == ClinicalSignCategory.EMERGENCY) {
                if (isAtOrAbove(currentCategory, TriageCategory.RED)) return NO_ACTION;
                return new AutoBump(
                        TriageCategory.RED,
                        "Emergency sign positive: " + signLabelForMessages);
            }

            // PEDIATRIC_EMERGENCY likewise → RED, but only on a pediatric
            // patient. On an adult visit we treat it as defensive NoAction
            // rather than escalate on what's structurally an unrelated form.
            if (signCategory == ClinicalSignCategory.PEDIATRIC_EMERGENCY) {
                if (!isPediatric) return NO_ACTION;
                if (isAtOrAbove(currentCategory, TriageCategory.RED)) return NO_ACTION;
                return new AutoBump(
                        TriageCategory.RED,
                        "Pediatric emergency sign positive: " + signLabelForMessages);
            }

            // mSAT Very Urgent → suggest if currently below ORANGE. We don't
            // auto-bump here because the resulting category in the Rwandan
            // flowchart depends on additional vitals + discriminators; the
            // nurse needs to look at the patient.
            if (signCategory == ClinicalSignCategory.MSAT_VU) {
                if (isAtOrAbove(currentCategory, TriageCategory.ORANGE)) return NO_ACTION;
                return new Suggest(
                        AlertSeverity.HIGH,
                        "Re-triage suggested: very urgent sign \"" + signLabelForMessages
                                + "\" reported. Current category: "
                                + safeCategoryName(currentCategory) + ".");
            }

            // mSAT Urgent → suggest if currently below YELLOW.
            if (signCategory == ClinicalSignCategory.MSAT_URG) {
                if (isAtOrAbove(currentCategory, TriageCategory.YELLOW)) return NO_ACTION;
                return new Suggest(
                        AlertSeverity.HIGH,
                        "Re-triage suggested: urgent sign \"" + signLabelForMessages
                                + "\" reported. Current category: "
                                + safeCategoryName(currentCategory) + ".");
            }

            // SPECIAL — informational only; never triggers a re-triage.
            return NO_ACTION;
        }

        // Down-trajectory branch (Round 4b):
        //
        // We never auto-de-escalate. A patient improving doesn't mean the
        // ED has spare capacity to re-bed them; that's a clinical
        // decision the nurse should make with the patient in front of
        // them. We only Suggest, and only when ALL of:
        //   - the new status is ABSENT or IMPROVING (a real signal that
        //     the sign is no longer driving acuity)
        //   - the previous status was PRESENT or WORSENING (otherwise
        //     this isn't a transition worth flagging)
        //   - the visit's current category is at or above the floor
        //     this sign would have implied (so the sign was plausibly a
        //     driver of the current category)
        //
        // We use MEDIUM severity rather than HIGH — down-bump suggestions
        // are reassurance-coloured, not alarm-coloured. The same alert
        // type RETRIAGE_REQUIRED is reused; the title distinguishes
        // direction.
        if (newStatus == ClinicalSignStatus.ABSENT || newStatus == ClinicalSignStatus.IMPROVING) {

            if (previousStatus != ClinicalSignStatus.PRESENT
                    && previousStatus != ClinicalSignStatus.WORSENING) {
                return NO_ACTION;
            }

            // Determine the floor this sign category would have implied.
            // Same thresholds as the up-bump branch.
            TriageCategory floor;
            if (signCategory == ClinicalSignCategory.EMERGENCY) {
                floor = TriageCategory.RED;
            } else if (signCategory == ClinicalSignCategory.PEDIATRIC_EMERGENCY) {
                if (!isPediatric) return NO_ACTION;
                floor = TriageCategory.RED;
            } else if (signCategory == ClinicalSignCategory.MSAT_VU) {
                floor = TriageCategory.ORANGE;
            } else if (signCategory == ClinicalSignCategory.MSAT_URG) {
                floor = TriageCategory.YELLOW;
            } else {
                // SPECIAL — never triggers down-bump suggestions either.
                return NO_ACTION;
            }

            // Only suggest when the patient is plausibly held at this
            // floor by the sign that's now improving. If the visit is
            // already below the floor, the sign wasn't driving acuity.
            if (!isAtOrAbove(currentCategory, floor)) return NO_ACTION;

            String dirLabel = newStatus == ClinicalSignStatus.ABSENT ? "resolved" : "improving";
            return new Suggest(
                    AlertSeverity.MEDIUM,
                    "Re-triage suggested: \"" + signLabelForMessages + "\" "
                            + dirLabel + ". The patient may no longer require "
                            + safeCategoryName(currentCategory)
                            + " care if no other driving signs remain.");
        }

        // UNKNOWN, anything else: NoAction.
        return NO_ACTION;
    }

    /**
     * Round 3 backwards-compatible overload — assumes no previous-status
     * context. Equivalent to passing {@code previousStatus = null}, which
     * disables the down-bump branch (down-bumps need to know what we
     * came from). Existing callers that haven't yet been ported get the
     * Round 3 behaviour unchanged.
     */
    public static RetriageDecision evaluate(
            ClinicalSignCategory signCategory,
            ClinicalSignStatus newStatus,
            boolean isBaseline,
            boolean isPediatric,
            TriageCategory currentCategory,
            String signLabelForMessages
    ) {
        return evaluate(signCategory, newStatus, null, isBaseline, isPediatric,
                currentCategory, signLabelForMessages);
    }

    /**
     * True when {@code current} is at least as severe as {@code threshold}.
     * Severity is the integer weight on TriageCategory (RED=4, ORANGE=3,
     * YELLOW=2, GREEN=1, BLUE=0). A null current category is treated as
     * the lowest severity so any positive worsening triggers the bump
     * branch.
     */
    private static boolean isAtOrAbove(TriageCategory current, TriageCategory threshold) {
        if (current == null) return false;
        return current.getSeverity() >= threshold.getSeverity();
    }

    private static String safeCategoryName(TriageCategory c) {
        return c == null ? "not yet triaged" : c.name();
    }
}
