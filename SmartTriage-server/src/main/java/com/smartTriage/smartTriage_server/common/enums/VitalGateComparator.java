package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Comparator for a PRN vitals gate (Medication Management, V67).
 * "Administer only if &lt;parameter&gt; &lt;comparator&gt; &lt;threshold&gt;".
 */
@Getter
@RequiredArgsConstructor
public enum VitalGateComparator {

    GTE("≥"),
    LTE("≤");

    private final String symbol;

    public boolean evaluate(double actual, double threshold) {
        return this == GTE ? actual >= threshold : actual <= threshold;
    }
}
