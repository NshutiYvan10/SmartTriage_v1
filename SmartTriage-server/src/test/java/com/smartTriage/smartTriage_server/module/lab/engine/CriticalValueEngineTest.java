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
    void panelWithoutThresholds_isNormal() {
        // A panel (FBC) carries no catalog thresholds and its name matches no keyword
        // analyte, so a single numeric result is not auto-flagged (documents the
        // multi-analyte panel limitation).
        var result = engine.evaluateResult(order("Full Blood Count", "3.0", 3.0, "x10^9/L"), null);
        assertThat(result.isCritical()).isFalse();
    }
}
