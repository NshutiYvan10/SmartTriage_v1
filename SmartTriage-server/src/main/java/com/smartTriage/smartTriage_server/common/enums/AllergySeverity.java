package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Clinical severity of a recorded patient allergy.
 *
 * <p>Drives the prescribe-time safety dialog flavour and the alert
 * severity when a prescriber overrides the check. Replacing the
 * previous all-or-nothing free-text model — every allergy used to
 * trip a CRITICAL hard-stop modal, which trained clinicians to
 * override reflexively (alert fatigue). With graded severity:
 *
 * <ul>
 *   <li>{@link #MILD} — soft warning, single acknowledge. No second
 *       clinician needed. Examples: localised rash, mild GI upset.</li>
 *   <li>{@link #MODERATE} — hard warning. Override requires a
 *       free-text reason. Examples: widespread urticaria, mild
 *       bronchospasm.</li>
 *   <li>{@link #SEVERE} — hard stop. Override fires a CRITICAL
 *       alert visible to the entire department. Examples: angioedema,
 *       severe bronchospasm.</li>
 *   <li>{@link #ANAPHYLAXIS} — hardest stop. Override fires a
 *       CRITICAL alert AND is escalated to a senior clinician via
 *       the existing alert pipeline. Life-threatening reaction
 *       (shock, airway compromise).</li>
 *   <li>{@link #UNKNOWN} — patient reports an allergy but can't
 *       describe the reaction. Treated as MODERATE for safety —
 *       we don't downgrade unknown to mild.</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum AllergySeverity {

    MILD("Mild", "Localised rash or mild GI upset"),
    MODERATE("Moderate", "Widespread urticaria or mild bronchospasm"),
    SEVERE("Severe", "Angioedema or severe bronchospasm"),
    ANAPHYLAXIS("Anaphylaxis", "Shock or airway compromise — life-threatening"),
    UNKNOWN("Unknown", "Patient reports an allergy but reaction details are not known");

    private final String label;
    private final String description;

    /**
     * Is this severity strong enough to warrant blocking the
     * prescription unless the prescriber acknowledges an override?
     * MILD allergies are soft warnings; everything else is a block
     * until overridden.
     */
    public boolean isBlocking() {
        return this != MILD;
    }

    /**
     * Should an override of this allergy generate a CRITICAL alert
     * (vs HIGH or MEDIUM)? Severe + anaphylaxis overrides need
     * department-wide visibility because the prescriber accepted
     * a high-regret risk.
     */
    public boolean overrideIsCritical() {
        return this == SEVERE || this == ANAPHYLAXIS;
    }

    /**
     * Risk rank used by the safety engine when multiple recorded
     * allergies match the same prescription — the highest-rank
     * severity wins and drives the dialog flavour. {@code UNKNOWN}
     * is deliberately ranked alongside {@code MODERATE} because we
     * cannot safely downgrade an allergy whose reaction we don't
     * know.
     */
    public int rank() {
        return switch (this) {
            case MILD -> 1;
            case MODERATE -> 2;
            case UNKNOWN -> 2;
            case SEVERE -> 3;
            case ANAPHYLAXIS -> 4;
        };
    }
}
