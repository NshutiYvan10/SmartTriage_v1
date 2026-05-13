package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How confident is the clinical record that this allergy is real?
 *
 * <p>Most ED-entered allergies start as {@link #PATIENT_REPORTED}.
 * A doctor reviewing the chart can promote to {@link #CONFIRMED}
 * (e.g. they saw the reaction documented in an earlier visit) or
 * demote to {@link #REFUTED} (allergy tested or clinically reviewed
 * and determined to be intolerance, not a true allergy). We do not
 * hard-delete the row — the refute event is itself an audit fact.
 */
@Getter
@RequiredArgsConstructor
public enum AllergyVerificationStatus {

    PATIENT_REPORTED("Patient-reported", "The patient (or family) reported this allergy"),
    CONFIRMED("Confirmed", "Reaction documented in this or a prior visit"),
    REFUTED("Refuted", "Reviewed and determined not to be a true allergy");

    private final String label;
    private final String description;
}
