package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What is actually being administered (Medication Management, V67).
 *
 * <p>"Special administrations" — blood transfusions, blood products,
 * IV fluid boluses — follow the same prescription→administration
 * workflow as drugs but are not formulary medications. The product
 * type is orthogonal to {@link PrescriptionType} (a PRBC transfusion
 * is BLOOD_PRODUCT + ONE_TIME; an insulin infusion is DRUG +
 * CONTINUOUS).
 *
 * <p>{@link #BLOOD_PRODUCT} administrations always require a second
 * clinician witness at the bedside (two-person verification is the
 * universal transfusion-safety standard), enforced at dose-recording
 * time regardless of formulary flags.
 */
@Getter
@RequiredArgsConstructor
public enum MedicationProductType {

    DRUG("Medication", false),
    BLOOD_PRODUCT("Blood product", true),
    IV_FLUID("IV fluid", false),
    OTHER("Other intervention", false);

    private final String label;
    /** TRUE when administrations of this product always need a witness. */
    private final boolean alwaysRequiresWitness;
}
