package com.smartTriage.smartTriage_server.module.lab.engine;

import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CriticalValueEngine — evaluates lab results against critical value thresholds
 * per Rwanda lab standards.
 *
 * Critical values require immediate clinician notification and acknowledgement.
 * In the Rwanda context, malaria-associated severe anemia (Hgb < 5 g/dL) and
 * positive malaria with high parasitemia are especially important.
 */
@Slf4j
@Component
public class CriticalValueEngine {

    /**
     * Evaluate a lab result for critical values.
     *
     * @param order the lab order with result data
     * @return critical value evaluation result
     */
    public CriticalValueResult evaluateResult(LabOrder order) {
        if (order.getResultValue() == null || order.getResultValue().isBlank()) {
            return CriticalValueResult.normal();
        }

        String testNameLower = order.getTestName().toLowerCase().trim();
        Double numericResult = order.getResultNumeric();
        String resultText = order.getResultValue().toLowerCase().trim();

        // Malaria check (text-based — critical in Rwanda context)
        if (testNameLower.contains("malaria") || testNameLower.contains("rdt")
                || testNameLower.contains("blood smear") || testNameLower.contains("parasit")) {
            if (resultText.contains("positive") || resultText.contains("pos")
                    || resultText.contains("+") || resultText.contains("detected")) {
                log.warn("CRITICAL: Malaria positive result for order {}", order.getOrderNumber());
                return CriticalValueResult.critical(
                        CriticalValueType.MALARIA_POSITIVE,
                        "Malaria POSITIVE — immediate treatment required");
            }
        }

        // Troponin check (text-based for qualitative results)
        if (testNameLower.contains("troponin")) {
            if (resultText.contains("positive") || resultText.contains("elevated")
                    || resultText.contains("high") || resultText.contains("abnormal")) {
                log.warn("CRITICAL: Troponin elevated for order {}", order.getOrderNumber());
                return CriticalValueResult.critical(
                        CriticalValueType.TROPONIN_HIGH,
                        "Troponin elevated — possible myocardial infarction");
            }
            if (numericResult != null && order.getReferenceRangeMax() != null
                    && numericResult > order.getReferenceRangeMax()) {
                log.warn("CRITICAL: Troponin {} above reference max {} for order {}",
                        numericResult, order.getReferenceRangeMax(), order.getOrderNumber());
                return CriticalValueResult.critical(
                        CriticalValueType.TROPONIN_HIGH,
                        String.format("Troponin %.3f above reference range (max %.3f) — possible MI",
                                numericResult, order.getReferenceRangeMax()));
            }
        }

        // Numeric value checks
        if (numericResult == null) {
            return CriticalValueResult.normal();
        }

        // Potassium
        if (testNameLower.contains("potassium") || testNameLower.equals("k+") || testNameLower.equals("k")) {
            if (numericResult > 6.0) {
                return criticalResult(CriticalValueType.POTASSIUM_HIGH, order,
                        "Potassium %.1f mmol/L (>6.0) — risk of cardiac arrhythmia");
            }
            if (numericResult < 2.5) {
                return criticalResult(CriticalValueType.POTASSIUM_LOW, order,
                        "Potassium %.1f mmol/L (<2.5) — risk of cardiac arrhythmia");
            }
        }

        // Sodium
        if (testNameLower.contains("sodium") || testNameLower.equals("na+") || testNameLower.equals("na")) {
            if (numericResult > 160.0) {
                return criticalResult(CriticalValueType.SODIUM_HIGH, order,
                        "Sodium %.1f mmol/L (>160) — severe hypernatremia");
            }
            if (numericResult < 120.0) {
                return criticalResult(CriticalValueType.SODIUM_LOW, order,
                        "Sodium %.1f mmol/L (<120) — severe hyponatremia, seizure risk");
            }
        }

        // Glucose
        if (testNameLower.contains("glucose") || testNameLower.contains("blood sugar")
                || testNameLower.equals("rbs") || testNameLower.equals("fbs")) {
            if (numericResult > 25.0) {
                return criticalResult(CriticalValueType.GLUCOSE_HIGH, order,
                        "Glucose %.1f mmol/L (>25) — severe hyperglycemia, DKA risk");
            }
            if (numericResult < 2.5) {
                return criticalResult(CriticalValueType.GLUCOSE_LOW, order,
                        "Glucose %.1f mmol/L (<2.5) — severe hypoglycemia, seizure risk");
            }
        }

        // Hemoglobin (important for Rwanda — malaria-associated anemia)
        if (testNameLower.contains("hemoglobin") || testNameLower.contains("haemoglobin")
                || testNameLower.equals("hgb") || testNameLower.equals("hb")) {
            if (numericResult < 5.0) {
                return criticalResult(CriticalValueType.HEMOGLOBIN_LOW, order,
                        "Hemoglobin %.1f g/dL (<5) — severe anemia, transfusion required");
            }
        }

        // Platelets
        if (testNameLower.contains("platelet") || testNameLower.equals("plt")) {
            if (numericResult < 20000) {
                return criticalResult(CriticalValueType.PLATELET_LOW, order,
                        "Platelets %.0f (<20,000) — severe thrombocytopenia, bleeding risk");
            }
        }

        // WBC
        if (testNameLower.contains("wbc") || testNameLower.contains("white blood cell")
                || testNameLower.contains("white cell count")) {
            if (numericResult > 30000) {
                return criticalResult(CriticalValueType.WBC_HIGH, order,
                        "WBC %.0f (>30,000) — severe leukocytosis");
            }
            if (numericResult < 1000) {
                return criticalResult(CriticalValueType.WBC_LOW, order,
                        "WBC %.0f (<1,000) — severe neutropenia, infection risk");
            }
        }

        // Creatinine
        if (testNameLower.contains("creatinine") || testNameLower.equals("cr")) {
            if (numericResult > 10.0) {
                return criticalResult(CriticalValueType.CREATININE_HIGH, order,
                        "Creatinine %.1f mg/dL (>10) — severe renal failure, dialysis may be needed");
            }
        }

        // Lactate (sepsis indicator)
        if (testNameLower.contains("lactate") || testNameLower.contains("lactic acid")) {
            if (numericResult > 4.0) {
                return criticalResult(CriticalValueType.LACTATE_HIGH, order,
                        "Lactate %.1f mmol/L (>4.0) — severe sepsis/shock indicator");
            }
        }

        // INR
        if (testNameLower.contains("inr") || testNameLower.contains("international normalized ratio")) {
            if (numericResult > 5.0) {
                return criticalResult(CriticalValueType.INR_HIGH, order,
                        "INR %.1f (>5.0) — severe coagulopathy, bleeding risk");
            }
        }

        // pH
        if (testNameLower.equals("ph") || testNameLower.contains("blood gas")
                || testNameLower.contains("abg")) {
            if (numericResult < 7.2) {
                return criticalResult(CriticalValueType.PH_LOW, order,
                        "pH %.2f (<7.2) — severe acidosis");
            }
            if (numericResult > 7.6) {
                return criticalResult(CriticalValueType.PH_HIGH, order,
                        "pH %.2f (>7.6) — severe alkalosis");
            }
        }

        return CriticalValueResult.normal();
    }

    private CriticalValueResult criticalResult(CriticalValueType type, LabOrder order, String formatMessage) {
        String message = String.format(formatMessage, order.getResultNumeric());
        log.warn("CRITICAL: {} for order {}", message, order.getOrderNumber());
        return CriticalValueResult.critical(type, message);
    }
}
