package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Critical laboratory value types with thresholds per Rwanda lab standards.
 */
@Getter
@RequiredArgsConstructor
public enum CriticalValueType {
    POTASSIUM_HIGH(">6.0 mmol/L"),
    POTASSIUM_LOW("<2.5 mmol/L"),
    SODIUM_HIGH(">160 mmol/L"),
    SODIUM_LOW("<120 mmol/L"),
    GLUCOSE_HIGH(">25 mmol/L"),
    GLUCOSE_LOW("<2.5 mmol/L"),
    HEMOGLOBIN_LOW("<5 g/dL"),
    PLATELET_LOW("<20,000"),
    WBC_HIGH(">30,000"),
    WBC_LOW("<1,000 (neutropenic)"),
    CREATININE_HIGH(">10 mg/dL"),
    LACTATE_HIGH(">4.0 mmol/L"),
    TROPONIN_HIGH("Above reference range"),
    INR_HIGH(">5.0"),
    PH_LOW("<7.2"),
    PH_HIGH(">7.6"),
    PO2_LOW("<8.0 kPa (severe hypoxemia)"),
    PCO2_HIGH(">9.5 kPa (hypercapnia / CO2 narcosis)"),
    BILIRUBIN_HIGH(">250 µmol/L"),
    MALARIA_POSITIVE("Critical in Rwanda context"),
    OTHER_CRITICAL("Other critical value");

    private final String description;
}
