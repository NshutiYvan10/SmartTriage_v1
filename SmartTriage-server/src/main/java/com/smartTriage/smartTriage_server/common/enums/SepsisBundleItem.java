package com.smartTriage.smartTriage_server.common.enums;

/**
 * Items in the 1-hour sepsis bundle, adapted for Rwanda's resource-limited settings.
 * Based on the Surviving Sepsis Campaign Hour-1 Bundle.
 */
public enum SepsisBundleItem {

    BLOOD_CULTURE_OBTAINED,
    BROAD_SPECTRUM_ANTIBIOTICS,
    IV_CRYSTALLOID_BOLUS,       // 30mL/kg for hypotension or lactate >= 4
    LACTATE_MEASURED,
    VASOPRESSORS_IF_NEEDED,     // If MAP < 65 after fluids
    REPEAT_LACTATE_IF_ELEVATED  // If initial lactate > 2 mmol/L
}
