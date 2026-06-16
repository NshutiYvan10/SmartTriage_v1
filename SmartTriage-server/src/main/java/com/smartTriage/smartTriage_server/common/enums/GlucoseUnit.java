package com.smartTriage.smartTriage_server.common.enums;

/**
 * Unit a glucose reading was entered in. SmartTriage stores and classifies
 * glucose in <b>mmol/L</b> (the unit every clinical threshold is expressed in),
 * but Rwandan glucometers commonly read in mg/dL. A reading therefore carries
 * its source unit so it can be converted to mmol/L at the boundary — range
 * checks alone cannot disambiguate the two scales (e.g. "18" is a plausible
 * mmol/L hyperglycemia AND a plausible mg/dL profound-hypoglycemia), so a
 * mis-scaled value would otherwise be silently misclassified.
 */
public enum GlucoseUnit {
    MMOL_L,
    MG_DL;

    /** Standard clinical conversion factor (mg/dL → mmol/L). */
    public static final double MG_DL_PER_MMOL_L = 18.0;

    /** Convert a value in this unit to mmol/L (the canonical storage/classification unit). */
    public double toMmolL(double value) {
        return this == MG_DL ? value / MG_DL_PER_MMOL_L : value;
    }
}
