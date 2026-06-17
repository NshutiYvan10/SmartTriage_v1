package com.smartTriage.smartTriage_server.module.lab.engine;

import com.smartTriage.smartTriage_server.common.enums.CriticalValueType;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.labcatalog.entity.LabTestCatalog;
import com.smartTriage.smartTriage_server.module.labcatalog.repository.LabTestCatalogRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CriticalValueEngine — catalog-driven, unit-safe critical-value detection.
 * Exercises the two-arg evaluateResult(order, catalog) directly so no DB is needed.
 */
class CriticalValueEngineTest {

    private final CriticalValueEngine engine =
            new CriticalValueEngine(mock(LabTestCatalogRepository.class));

    private LabOrder order(String testName, String value, Double numeric, String unit) {
        return LabOrder.builder()
                .orderNumber("LAB-TEST-1")
                .testName(testName)
                .resultValue(value)
                .resultNumeric(numeric)
                .resultUnit(unit)
                .build();
    }

    private LabTestCatalog catalog(String unit, Double critLow, Double critHigh) {
        return LabTestCatalog.builder()
                .testName("X").resultUnit(unit).criticalLow(critLow).criticalHigh(critHigh)
                .build();
    }

    // ── Catalog-driven detection ──

    @Test
    void catalogCriticalHigh_flagsCritical_withMatchingUnit() {
        var result = engine.evaluateResult(
                order("Random Blood Glucose", "30", 30.0, "mmol/L"),
                catalog("mmol/L", 2.5, 25.0));
        assertThat(result.isCritical()).isTrue();
        assertThat(result.criticalValueType()).isEqualTo(CriticalValueType.GLUCOSE_HIGH);
    }

    @Test
    void catalogWithinRange_isNormal() {
        var result = engine.evaluateResult(
                order("Random Blood Glucose", "6", 6.0, "mmol/L"),
                catalog("mmol/L", 2.5, 25.0));
        assertThat(result.isCritical()).isFalse();
    }

    @Test
    void unitMismatch_doesNotMisFlag() {
        // 300 mg/dL glucose (~16.6 mmol/L, NOT critical) must NOT be compared against the
        // mmol/L critical-high of 25 — the engine returns normal rather than mis-flagging.
        var result = engine.evaluateResult(
                order("Random Blood Glucose", "300", 300.0, "mg/dL"),
                catalog("mmol/L", 2.5, 25.0));
        assertThat(result.isCritical()).isFalse();
    }

    @Test
    void blankUnit_isTreatedAsCanonical() {
        var result = engine.evaluateResult(
                order("Random Blood Glucose", "30", 30.0, ""),
                catalog("mmol/L", 2.5, 25.0));
        assertThat(result.isCritical()).isTrue();
    }

    // ── Keyword fallback (no catalog entry) ──

    @Test
    void keywordFallback_potassiumHigh_flagsCritical() {
        var result = engine.evaluateResult(order("Potassium", "7.1", 7.1, "mmol/L"), null);
        assertThat(result.isCritical()).isTrue();
        assertThat(result.criticalValueType()).isEqualTo(CriticalValueType.POTASSIUM_HIGH);
    }

    @Test
    void keywordFallback_isUnitGated() {
        // A potassium of 7 entered in mg/dL must not be compared against the mmol/L rule.
        var result = engine.evaluateResult(order("Potassium", "7.1", 7.1, "mg/dL"), null);
        assertThat(result.isCritical()).isFalse();
    }

    @Test
    void malariaPositive_isCritical_regardlessOfUnit() {
        var result = engine.evaluateResult(
                order("Malaria Rapid Diagnostic Test", "POSITIVE", null, null), null);
        assertThat(result.isCritical()).isTrue();
        assertThat(result.criticalValueType()).isEqualTo(CriticalValueType.MALARIA_POSITIVE);
    }

    @Test
    void catalogThreshold_isInclusive() {
        // A value exactly at the panic threshold must be caught (>= / <=).
        var result = engine.evaluateResult(
                order("Random Blood Glucose", "25", 25.0, "mmol/L"),
                catalog("mmol/L", 2.5, 25.0));
        assertThat(result.isCritical()).isTrue();
    }

    @Test
    void presentWrongUnit_fallsThroughToKeyword_creatinineMgDl() {
        // Catalog creatinine is µmol/L; a result legitimately in mg/dL must NOT be
        // compared to the µmol/L threshold but SHOULD be caught by the mg/dL keyword rule.
        var result = engine.evaluateResult(
                order("Creatinine", "12", 12.0, "mg/dL"),
                catalog("µmol/L", null, 500.0));
        assertThat(result.isCritical()).isTrue();
    }

    @Test
    void bloodGasKeyword_requiresExplicitUnit() {
        // A blood-gas panel number with a BLANK unit must NOT be auto-flagged as pH
        // (it could be pO2/pCO2) — the panel keyword rule requires an explicit pH unit.
        var blankUnit = engine.evaluateResult(order("Arterial Blood Gas", "7.0", 7.0, ""), null);
        assertThat(blankUnit.isCritical()).isFalse();
        // With an explicit pH unit a genuinely low pH IS flagged.
        var explicitPh = engine.evaluateResult(order("Arterial Blood Gas", "7.0", 7.0, "pH"), null);
        assertThat(explicitPh.isCritical()).isTrue();
    }

    @Test
    void panelWithoutThresholds_singleResultPath_isNormal() {
        // The SINGLE-result path can't evaluate a panel (FBC) — its components carry the
        // thresholds (see evaluateComponent tests below). A single numeric for the whole
        // panel is therefore not auto-flagged here.
        var result = engine.evaluateResult(order("Full Blood Count", "3.0", 3.0, "x10^9/L"), null);
        assertThat(result.isCritical()).isFalse();
    }

    // ── Per-analyte (panel component) detection ──

    @Test
    void component_criticalLow_flagged() {
        // K+ 6.8 inside a U&E — at/above the 6.0 critical-high → critical.
        var r = engine.evaluateComponent("Potassium", 6.8, "mmol/L", "mmol/L", 2.5, 6.0);
        assertThat(r.isCritical()).isTrue();
        assertThat(r.criticalValueType()).isEqualTo(CriticalValueType.POTASSIUM_HIGH);
    }

    @Test
    void component_hypoxemia_pO2_flagged() {
        // pO2 6.0 kPa inside a blood gas — the value the single-result model could never
        // catch — is at/below the 8.0 critical-low → critical, with the specific PO2_LOW type.
        var r = engine.evaluateComponent("pO2", 6.0, "kPa", "kPa", 8.0, null);
        assertThat(r.isCritical()).isTrue();
        assertThat(r.criticalValueType()).isEqualTo(CriticalValueType.PO2_LOW);
    }

    @Test
    void component_hypercapnia_pCO2_flagged() {
        // pCO2 10 kPa inside a blood gas — at/above the 9.5 critical-high (CO2 narcosis) →
        // critical, typed PCO2_HIGH.
        var r = engine.evaluateComponent("pCO2", 10.0, "kPa", "kPa", null, 9.5);
        assertThat(r.isCritical()).isTrue();
        assertThat(r.criticalValueType()).isEqualTo(CriticalValueType.PCO2_HIGH);
    }

    @Test
    void component_withinRange_isNormal() {
        var r = engine.evaluateComponent("Potassium", 4.2, "mmol/L", "mmol/L", 2.5, 6.0);
        assertThat(r.isCritical()).isFalse();
    }

    @Test
    void component_unitMismatch_suppressesAutoCritical() {
        // A potassium value reported in mg/dL must NOT be compared against the mmol/L
        // critical thresholds — returns normal (caller flags abnormal + verify-manually).
        var r = engine.evaluateComponent("Potassium", 7.0, "mg/dL", "mmol/L", 2.5, 6.0);
        assertThat(r.isCritical()).isFalse();
    }

    @Test
    void component_blankUnit_treatedAsCanonical() {
        // The analyte identity is unambiguous (from the panel definition), so a blank
        // entered unit is safely treated as the component's canonical unit.
        var r = engine.evaluateComponent("Potassium", 6.5, "", "mmol/L", 2.5, 6.0);
        assertThat(r.isCritical()).isTrue();
    }

    @Test
    void component_nullNumericOrNoThresholds_isNormal() {
        assertThat(engine.evaluateComponent("Urea", null, "mmol/L", "mmol/L", null, null).isCritical()).isFalse();
        assertThat(engine.evaluateComponent("Urea", 9.0, "mmol/L", "mmol/L", null, null).isCritical()).isFalse();
    }

    @Test
    void component_inclusiveAtThreshold_flagged() {
        // Exactly at the critical-low boundary (Hb 5.0 g/dL, crit_low 5) → critical (<=).
        var r = engine.evaluateComponent("Hemoglobin", 5.0, "g/dL", "g/dL", 5.0, null);
        assertThat(r.isCritical()).isTrue();
        assertThat(r.criticalValueType()).isEqualTo(CriticalValueType.HEMOGLOBIN_LOW);
    }
}
