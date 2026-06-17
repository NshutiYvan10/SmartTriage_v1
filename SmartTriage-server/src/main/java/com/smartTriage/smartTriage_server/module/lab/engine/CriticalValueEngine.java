package com.smartTriage.smartTriage_server.module.lab.engine;

import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.labcatalog.entity.LabTestCatalog;
import com.smartTriage.smartTriage_server.module.labcatalog.repository.LabTestCatalogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CriticalValueEngine — evaluates lab results against critical (panic) thresholds.
 *
 * <p>Detection is now CATALOG-DRIVEN and UNIT-SAFE. For a test that carries
 * critical thresholds in {@link LabTestCatalog}, a numeric result is evaluated
 * against those thresholds ONLY when the entered result unit matches the catalog's
 * canonical unit — so a value reported in a different unit (e.g. glucose in mg/dL
 * vs the mmol/L thresholds) is never silently mis-evaluated. Tests not yet in the
 * catalog (or without thresholds) fall back to the built-in keyword rules, which
 * are now likewise unit-gated. Qualitative results (malaria / troponin text) are
 * matched by name regardless of unit.
 *
 * <p>A panel (FBC, U&E, …) yields many analytes. Those are evaluated per-analyte via
 * {@link #evaluateComponent} against each analyte's own unit + thresholds (defined in
 * {@code lab_panel_component}, V83), so a single critical analyte (e.g. K+ inside a U&E
 * or pO2 inside a blood gas) is detected even when the rest of the panel is normal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticalValueEngine {

    private final LabTestCatalogRepository labTestCatalogRepository;

    /** Resolve the catalog entry backing a placed order (by test name, then short name). */
    public LabTestCatalog resolveCatalog(LabOrder order) {
        if (order == null) return null;
        try {
            if (order.getTestName() != null && !order.getTestName().isBlank()) {
                var byName = labTestCatalogRepository
                        .findFirstByTestNameIgnoreCaseAndIsActiveTrue(order.getTestName().trim());
                if (byName.isPresent()) return byName.get();
                var byShort = labTestCatalogRepository
                        .findFirstByShortNameIgnoreCaseAndIsActiveTrue(order.getTestName().trim());
                if (byShort.isPresent()) return byShort.get();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve lab catalog for order {}: {}",
                    order.getOrderNumber(), e.getMessage());
        }
        return null;
    }

    /** Convenience overload — resolves the catalog itself (used by fallback re-eval paths). */
    public CriticalValueResult evaluateResult(LabOrder order) {
        return evaluateResult(order, resolveCatalog(order));
    }

    /**
     * Evaluate a lab result for critical values, using the supplied catalog entry
     * (may be null) for thresholds + the canonical unit.
     */
    public CriticalValueResult evaluateResult(LabOrder order, LabTestCatalog catalog) {
        if (order.getResultValue() == null || order.getResultValue().isBlank()) {
            return CriticalValueResult.normal();
        }

        String testNameLower = order.getTestName() != null ? order.getTestName().toLowerCase().trim() : "";
        Double numericResult = order.getResultNumeric();
        String resultText = order.getResultValue().toLowerCase().trim();

        // ── Qualitative (text) rules — unit-independent ──
        // Malaria (critical in the Rwanda context).
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

        // Troponin (assay-specific — qualitative text + reference-range comparison; no fixed numeric threshold).
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

        if (numericResult == null) {
            return CriticalValueResult.normal();
        }

        // ── Catalog-driven numeric thresholds (preferred, unit-gated) ──
        // Evaluate only when the result unit is compatible with the catalog's canonical
        // unit (a blank unit is assumed canonical so a missing unit never MISSES a
        // critical). Inclusive >=/<= so a value exactly at the panic threshold is caught.
        // A PRESENT-but-different unit falls THROUGH to the keyword rules below — each is
        // gated to its own canonical unit, so a value reported in a different KNOWN unit
        // (e.g. creatinine in mg/dL) is still caught; if nothing catches it, the service
        // surfaces the mismatch as abnormal + a verify-manually note.
        if (catalog != null && (catalog.getCriticalLow() != null || catalog.getCriticalHigh() != null)
                && unitCompatible(order.getResultUnit(), catalog.getResultUnit())) {
            String unit = catalog.getResultUnit() != null ? catalog.getResultUnit() : "";
            if (catalog.getCriticalHigh() != null && numericResult >= catalog.getCriticalHigh()) {
                return catalogCritical(order, inferType(testNameLower, true), numericResult, unit,
                        "at/above critical high", catalog.getCriticalHigh());
            }
            if (catalog.getCriticalLow() != null && numericResult <= catalog.getCriticalLow()) {
                return catalogCritical(order, inferType(testNameLower, false), numericResult, unit,
                        "at/below critical low", catalog.getCriticalLow());
            }
            return CriticalValueResult.normal();
        }

        // ── Built-in keyword fallback (tests not in the catalog / without thresholds) ──
        // Each numeric rule is gated on the result unit so a value in a different unit
        // system is not mis-flagged. A blank unit is treated as the canonical unit.

        if (testNameLower.contains("potassium") || testNameLower.equals("k+") || testNameLower.equals("k")) {
            if (numericUnitOk(order, "mmol/L")) {
                if (numericResult > 6.0) return kw(CriticalValueType.POTASSIUM_HIGH, order,
                        "Potassium %.1f mmol/L (>6.0) — risk of cardiac arrhythmia");
                if (numericResult < 2.5) return kw(CriticalValueType.POTASSIUM_LOW, order,
                        "Potassium %.1f mmol/L (<2.5) — risk of cardiac arrhythmia");
            }
        }
        if (testNameLower.contains("sodium") || testNameLower.equals("na+") || testNameLower.equals("na")) {
            if (numericUnitOk(order, "mmol/L")) {
                if (numericResult > 160.0) return kw(CriticalValueType.SODIUM_HIGH, order,
                        "Sodium %.1f mmol/L (>160) — severe hypernatremia");
                if (numericResult < 120.0) return kw(CriticalValueType.SODIUM_LOW, order,
                        "Sodium %.1f mmol/L (<120) — severe hyponatremia, seizure risk");
            }
        }
        if (testNameLower.contains("glucose") || testNameLower.contains("blood sugar")
                || testNameLower.equals("rbs") || testNameLower.equals("fbs")) {
            if (numericUnitOk(order, "mmol/L")) {
                if (numericResult > 25.0) return kw(CriticalValueType.GLUCOSE_HIGH, order,
                        "Glucose %.1f mmol/L (>25) — severe hyperglycemia, DKA risk");
                if (numericResult < 2.5) return kw(CriticalValueType.GLUCOSE_LOW, order,
                        "Glucose %.1f mmol/L (<2.5) — severe hypoglycemia, seizure risk");
            }
        }
        if (testNameLower.contains("hemoglobin") || testNameLower.contains("haemoglobin")
                || testNameLower.equals("hgb") || testNameLower.equals("hb")) {
            if (numericUnitOk(order, "g/dL") && numericResult < 5.0) {
                return kw(CriticalValueType.HEMOGLOBIN_LOW, order,
                        "Hemoglobin %.1f g/dL (<5) — severe anemia, transfusion required");
            }
        }
        if (testNameLower.contains("platelet") || testNameLower.equals("plt")) {
            if (numericUnitOk(order, "/uL", "cells/uL", "/µL") && numericResult < 20000) {
                return kw(CriticalValueType.PLATELET_LOW, order,
                        "Platelets %.0f (<20,000) — severe thrombocytopenia, bleeding risk");
            }
        }
        if (testNameLower.contains("wbc") || testNameLower.contains("white blood cell")
                || testNameLower.contains("white cell count")) {
            if (numericUnitOk(order, "/uL", "cells/uL", "/µL")) {
                if (numericResult > 30000) return kw(CriticalValueType.WBC_HIGH, order,
                        "WBC %.0f (>30,000) — severe leukocytosis");
                if (numericResult < 1000) return kw(CriticalValueType.WBC_LOW, order,
                        "WBC %.0f (<1,000) — severe neutropenia, infection risk");
            }
        }
        if (testNameLower.contains("creatinine") || testNameLower.equals("cr")) {
            if (numericUnitOk(order, "mg/dL") && numericResult > 10.0) {
                return kw(CriticalValueType.CREATININE_HIGH, order,
                        "Creatinine %.1f mg/dL (>10) — severe renal failure, dialysis may be needed");
            }
        }
        if (testNameLower.contains("lactate") || testNameLower.contains("lactic acid")) {
            if (numericUnitOk(order, "mmol/L") && numericResult > 4.0) {
                return kw(CriticalValueType.LACTATE_HIGH, order,
                        "Lactate %.1f mmol/L (>4.0) — severe sepsis/shock indicator");
            }
        }
        // INR and blood-gas are multi-analyte PANEL orders — a blank unit can't identify
        // which analyte the number is, so these require an EXPLICIT matching unit (no
        // blank-as-canonical) to avoid mis-evaluating e.g. a pO2/aPTT as pH/INR.
        if (testNameLower.contains("inr") || testNameLower.contains("international normalized ratio")) {
            if (numericUnitStrict(order, "INR", "ratio") && numericResult > 5.0) {
                return kw(CriticalValueType.INR_HIGH, order,
                        "INR %.1f (>5.0) — severe coagulopathy, bleeding risk");
            }
        }
        if (testNameLower.equals("ph") || testNameLower.contains("blood gas") || testNameLower.contains("abg")) {
            if (numericUnitStrict(order, "pH")) {
                if (numericResult < 7.2) return kw(CriticalValueType.PH_LOW, order,
                        "pH %.2f (<7.2) — severe acidosis");
                if (numericResult > 7.6) return kw(CriticalValueType.PH_HIGH, order,
                        "pH %.2f (>7.6) — severe alkalosis");
            }
        }

        return CriticalValueResult.normal();
    }

    /** True when the entered unit can be safely compared against the canonical unit:
     *  a blank entered unit is treated as the canonical unit; otherwise they must match
     *  (case-, space- and µ/u-insensitive). */
    public boolean unitCompatible(String actual, String canonical) {
        if (actual == null || actual.isBlank()) return true;        // assume canonical
        if (canonical == null || canonical.isBlank()) return true;  // no canonical to enforce
        return normalizeUnit(actual).equals(normalizeUnit(canonical));
    }

    private boolean numericUnitOk(LabOrder order, String... canonicalUnits) {
        String actual = order.getResultUnit();
        if (actual == null || actual.isBlank()) return true;   // blank assumed canonical
        String norm = normalizeUnit(actual);
        for (String c : canonicalUnits) {
            if (norm.equals(normalizeUnit(c))) return true;
        }
        return false;
    }

    /** Like {@link #numericUnitOk} but a BLANK unit does NOT match — for multi-analyte
     *  panel rules (INR / blood-gas pH) where a missing unit can't identify the analyte. */
    private boolean numericUnitStrict(LabOrder order, String... canonicalUnits) {
        String actual = order.getResultUnit();
        if (actual == null || actual.isBlank()) return false;
        String norm = normalizeUnit(actual);
        for (String c : canonicalUnits) {
            if (norm.equals(normalizeUnit(c))) return true;
        }
        return false;
    }

    private String normalizeUnit(String u) {
        return u.toLowerCase().trim().replace(" ", "").replace("µ", "u").replace("μ", "u");
    }

    private CriticalValueResult catalogCritical(LabOrder order, CriticalValueType type,
            double value, String unit, String direction, double threshold) {
        String message = String.format("%s result %s %s is %s (%s) — immediate clinician acknowledgement required.",
                order.getTestName(), trimNumber(value), unit, direction, trimNumber(threshold));
        log.warn("CRITICAL (catalog): {} for order {}", message, order.getOrderNumber());
        return CriticalValueResult.critical(type, message);
    }

    private String trimNumber(double v) {
        return v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.2f", v);
    }

    /** Map a test name to its specific CriticalValueType, falling back to OTHER_CRITICAL. */
    private CriticalValueType inferType(String testNameLower, boolean high) {
        if (testNameLower.contains("potassium")) return high ? CriticalValueType.POTASSIUM_HIGH : CriticalValueType.POTASSIUM_LOW;
        if (testNameLower.contains("sodium")) return high ? CriticalValueType.SODIUM_HIGH : CriticalValueType.SODIUM_LOW;
        if (testNameLower.contains("glucose")) return high ? CriticalValueType.GLUCOSE_HIGH : CriticalValueType.GLUCOSE_LOW;
        if (testNameLower.contains("hemoglobin") || testNameLower.contains("haemoglobin")) return CriticalValueType.HEMOGLOBIN_LOW;
        if (testNameLower.contains("platelet")) return CriticalValueType.PLATELET_LOW;
        if (testNameLower.contains("wbc") || testNameLower.contains("white")) return high ? CriticalValueType.WBC_HIGH : CriticalValueType.WBC_LOW;
        if (testNameLower.contains("creatinine")) return CriticalValueType.CREATININE_HIGH;
        if (testNameLower.contains("lactate")) return CriticalValueType.LACTATE_HIGH;
        if (testNameLower.contains("inr") || testNameLower.contains("coagulation")) return CriticalValueType.INR_HIGH;
        // Blood-gas analytes — pO2/pCO2 carry their own specific types (per-analyte panel
        // detection); the generic pH match stays last so it doesn't shadow pco2.
        if (testNameLower.contains("po2") || testNameLower.contains("oxygen")) return CriticalValueType.PO2_LOW;
        if (testNameLower.contains("pco2") || testNameLower.contains("carbon dioxide")) return CriticalValueType.PCO2_HIGH;
        if (testNameLower.contains("bilirubin")) return CriticalValueType.BILIRUBIN_HIGH;
        if (testNameLower.contains("blood gas") || testNameLower.equals("ph") || testNameLower.contains(" ph")) {
            return high ? CriticalValueType.PH_HIGH : CriticalValueType.PH_LOW;
        }
        return CriticalValueType.OTHER_CRITICAL;
    }

    private CriticalValueResult kw(CriticalValueType type, LabOrder order, String formatMessage) {
        String message = String.format(formatMessage, order.getResultNumeric());
        log.warn("CRITICAL: {} for order {}", message, order.getOrderNumber());
        return CriticalValueResult.critical(type, message);
    }

    // ── Per-analyte (panel component) evaluation ──

    /**
     * Evaluate one analyte of a multi-analyte (panel) result against its OWN unit and
     * critical thresholds (carried by the panel-component definition).
     *
     * <p>Unlike the single-result keyword path, the analyte identity is unambiguous here
     * (it comes from the panel definition, not inferred from a free-text test name), so a
     * BLANK entered unit is safely treated as the component's canonical unit. A PRESENT but
     * incompatible unit suppresses auto-critical (the caller flags it abnormal + a
     * verify-manually note) so a value reported in the wrong unit is never mis-evaluated.
     *
     * @return a critical result if the value breaches a panic threshold, else normal.
     */
    public CriticalValueResult evaluateComponent(String analyteName, Double numeric, String enteredUnit,
            String canonicalUnit, Double criticalLow, Double criticalHigh) {
        if (numeric == null || (criticalLow == null && criticalHigh == null)) {
            return CriticalValueResult.normal();
        }
        if (!unitCompatible(enteredUnit, canonicalUnit)) {
            return CriticalValueResult.normal();   // wrong unit — caller marks abnormal + note
        }
        String nameLower = analyteName != null ? analyteName.toLowerCase().trim() : "";
        String unit = (canonicalUnit != null && !canonicalUnit.isBlank()) ? canonicalUnit
                : (enteredUnit != null ? enteredUnit : "");
        if (criticalHigh != null && numeric >= criticalHigh) {
            CriticalValueType type = inferType(nameLower, true);
            String message = String.format("%s %s %s at/above critical high (%s) — immediate clinician acknowledgement required.",
                    analyteName, trimNumber(numeric), unit, trimNumber(criticalHigh));
            log.warn("CRITICAL (component): {}", message);
            return CriticalValueResult.critical(type, message);
        }
        if (criticalLow != null && numeric <= criticalLow) {
            CriticalValueType type = inferType(nameLower, false);
            String message = String.format("%s %s %s at/below critical low (%s) — immediate clinician acknowledgement required.",
                    analyteName, trimNumber(numeric), unit, trimNumber(criticalLow));
            log.warn("CRITICAL (component): {}", message);
            return CriticalValueResult.critical(type, message);
        }
        return CriticalValueResult.normal();
    }
}
