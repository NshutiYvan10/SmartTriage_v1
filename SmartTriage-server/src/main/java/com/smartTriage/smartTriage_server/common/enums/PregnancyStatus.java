package com.smartTriage.smartTriage_server.common.enums;

/**
 * Pregnancy status — drives teratogen safety checks at prescribe time.
 *
 * Replaces the old free-text scan of chronic_conditions, which silently
 * missed misspellings, negations, and non-English variants and therefore
 * could let a teratogen be prescribed to a pregnant patient with no warning.
 *
 * State semantics (precise — these are the contract for the safety check):
 *   PREGNANT          — confirmed pregnancy. Block teratogens.
 *   BREASTFEEDING     — block drugs unsafe in lactation.
 *   POSSIBLY_PREGNANT — clinician suspects pregnancy (missed period, no
 *                       hCG yet). Treat as PREGNANT for safety until ruled out.
 *   NOT_PREGNANT      — confirmed by clinician (e.g. negative hCG, clear
 *                       history). Safe to prescribe normally.
 *   NOT_APPLICABLE    — patient cannot be pregnant by definition (definitively
 *                       male). Safety check skipped truthfully.
 *   UNKNOWN           — clinician was asked, status not yet confirmed. The
 *                       safety check MUST treat this as "ask before prescribing
 *                       teratogens". UNKNOWN is the safe default for any
 *                       record where pregnancy cannot be ruled out (FEMALE,
 *                       OTHER, UNKNOWN gender).
 *
 * Why not nullable: a NULL would let the safety check silently skip records.
 * The DB column is backfilled and creation paths assign a default — see
 * {@link #defaultFor(Gender)}.
 */
public enum PregnancyStatus {
    PREGNANT,
    BREASTFEEDING,
    POSSIBLY_PREGNANT,
    NOT_PREGNANT,
    NOT_APPLICABLE,
    UNKNOWN;

    /**
     * Returns the clinically-defensive default for a patient's gender at
     * registration time, before any clinician has affirmed a status.
     *
     * MALE                          → NOT_APPLICABLE (cannot be pregnant)
     * FEMALE / OTHER / UNKNOWN /null → UNKNOWN (must be confirmed before
     *                                  prescribing teratogens)
     *
     * OTHER and UNKNOWN gender intentionally fall through to UNKNOWN, not
     * NOT_APPLICABLE. Intersex, non-binary, or ambiguous records may have
     * reproductive organs — silently skipping the safety check would defeat
     * the purpose of the column.
     */
    public static PregnancyStatus defaultFor(Gender gender) {
        return gender == Gender.MALE ? NOT_APPLICABLE : UNKNOWN;
    }
}
