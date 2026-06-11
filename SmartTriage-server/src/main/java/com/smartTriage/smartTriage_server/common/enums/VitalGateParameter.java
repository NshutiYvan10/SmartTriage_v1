package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Vital-sign parameter a PRN order can be gated on
 * (Medication Management, V67).
 *
 * <p>Example orders: "Labetalol 20 mg IV PRN — only if SBP ≥ 180",
 * "Morphine 2 mg IV PRN pain — only if pain score ≥ 4 and RR ≥ 12".
 * The gate is evaluated against the visit's MOST RECENT vital-signs
 * reading at administration time; a stale or absent reading fails
 * closed (blocked until vitals are recorded or the nurse overrides
 * with justification).
 */
@Getter
@RequiredArgsConstructor
public enum VitalGateParameter {

    SYSTOLIC_BP("Systolic BP", "mmHg"),
    HEART_RATE("Heart rate", "bpm"),
    RESPIRATORY_RATE("Respiratory rate", "/min"),
    SPO2("SpO2", "%"),
    TEMPERATURE("Temperature", "°C"),
    PAIN_SCORE("Pain score", "/10");

    private final String label;
    private final String unit;
}
