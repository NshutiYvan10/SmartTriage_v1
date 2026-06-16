package com.smartTriage.smartTriage_server.module.sepsis.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.sepsis.engine.SepsisScreeningEngine.SepsisScreeningResult;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol-math evidence for the sepsis screening engine. Each test documents
 * the exact qSOFA/SIRS arithmetic by hand and asserts the engine matches — zero
 * discrepancy. Covers the full risk spectrum (negative / borderline / positive),
 * boundary values, adult vs pediatric thresholds, and missing-data handling.
 */
class SepsisScreeningEngineTest {

    private final SepsisScreeningEngine engine = new SepsisScreeningEngine();

    private VitalSigns vitals(Double temp, Integer hr, Integer rr, Integer sbp, AvpuScore avpu) {
        return VitalSigns.builder()
                .temperature(temp).heartRate(hr).respiratoryRate(rr).systolicBp(sbp).avpu(avpu)
                .build();
    }

    private Visit adultVisit() {
        Visit v = new Visit();
        v.setVisitNumber("V-ADULT");
        v.setPediatric(false);
        return v;
    }

    private Visit pediatricVisit(int ageYears) {
        Visit v = new Visit();
        v.setVisitNumber("V-PEDS");
        v.setPediatric(true);
        v.setPatient(Patient.builder().dateOfBirth(LocalDate.now().minusYears(ageYears)).build());
        return v;
    }

    private Visit pediatricVisitMonths(int ageMonths) {
        Visit v = new Visit();
        v.setVisitNumber("V-PEDS-M");
        v.setPediatric(true);
        v.setPatient(Patient.builder().dateOfBirth(LocalDate.now().minusMonths(ageMonths)).build());
        return v;
    }

    // ── Clearly negative ────────────────────────────────────────────

    @Test
    @DisplayName("Normal adult vitals → NO_SEPSIS (qSOFA 0, SIRS 0)")
    void clearlyNegative() {
        // temp 37 (no), HR 80 (≤90 no), RR 16 (no), SBP 120 (no), ALERT (no)
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, 80, 16, 120, AvpuScore.ALERT), adultVisit());
        assertEquals(0, r.qsofaScore());
        assertEquals(0, r.sirsScore());
        assertEquals(SepsisStatus.NO_SEPSIS, r.status());
        assertFalse(r.bundleRequired());
        assertFalse(r.insufficientData());
    }

    // ── qSOFA boundaries ────────────────────────────────────────────

    @Test
    @DisplayName("qSOFA boundary INCLUDED: RR=22 + SBP=100 → qSOFA 2 → SEPSIS_SUSPECTED")
    void qsofaBoundaryIncluded() {
        // RR 22 (>=22 ✓ +1), SBP 100 (<=100 ✓ +1), ALERT (no). qSOFA = 2.
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, 80, 22, 100, AvpuScore.ALERT), adultVisit());
        assertEquals(2, r.qsofaScore());
        assertEquals(SepsisStatus.SEPSIS_SUSPECTED, r.status());
        assertTrue(r.bundleRequired());
    }

    @Test
    @DisplayName("qSOFA boundary EXCLUDED: RR=21 + SBP=101 → qSOFA 0 (SIRS RR>20 still counts 1)")
    void qsofaBoundaryExcluded() {
        // qSOFA: RR 21 (<22 no), SBP 101 (>100 no) → 0.
        // SIRS: RR 21 (>20 ✓ +1); temp/HR normal → SIRS 1. Net NO_SEPSIS.
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, 80, 21, 101, AvpuScore.ALERT), adultVisit());
        assertEquals(0, r.qsofaScore());
        assertEquals(1, r.sirsScore());
        assertEquals(SepsisStatus.NO_SEPSIS, r.status());
    }

    // ── SIRS ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Adult SIRS: temp 39 + HR 110 → SIRS 2 → SIRS_POSITIVE (no bundle without infection)")
    void sirsPositiveAdult() {
        // temp 39 (>38 ✓ +1), HR 110 (>90 ✓ +1), RR 18 (no). SIRS 2. qSOFA 0.
        SepsisScreeningResult r = engine.screenForSepsis(vitals(39.0, 110, 18, 120, AvpuScore.ALERT), adultVisit());
        assertEquals(2, r.sirsScore());
        assertEquals(0, r.qsofaScore());
        assertEquals(SepsisStatus.SIRS_POSITIVE, r.status());
        assertFalse(r.bundleRequired()); // SIRS alone is not bundle-required
    }

    // ── Severe sepsis / septic shock ────────────────────────────────

    @Test
    @DisplayName("qSOFA 2 + SBP 85 → SEVERE_SEPSIS (organ dysfunction), bundle required")
    void severeSepsisFromHypotension() {
        // qSOFA: RR 24 (✓), SBP 85 (<=100 ✓) → 2 → SEPSIS_SUSPECTED; SBP<90 → SEVERE_SEPSIS.
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, 80, 24, 85, AvpuScore.ALERT), adultVisit());
        assertEquals(SepsisStatus.SEVERE_SEPSIS, r.status());
        assertTrue(r.bundleRequired());
    }

    @Test
    @DisplayName("qSOFA 2 + SBP 65 → SEPTIC_SHOCK")
    void septicShock() {
        // qSOFA: RR 24 (✓), SBP 65 (<=100 ✓) → 2 → SEPSIS_SUSPECTED; SBP<90 → SEVERE; SBP<70 → SHOCK.
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, 80, 24, 65, AvpuScore.ALERT), adultVisit());
        assertEquals(SepsisStatus.SEPTIC_SHOCK, r.status());
        assertTrue(r.bundleRequired());
    }

    @Test
    @DisplayName("Altered mentation (AVPU≠ALERT) + RR 24 → qSOFA 2 → SEPSIS_SUSPECTED")
    void alteredMentationQsofa() {
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, 80, 24, 120, AvpuScore.PAIN), adultVisit());
        assertTrue(r.alteredMentation());
        assertEquals(2, r.qsofaScore());
        assertEquals(SepsisStatus.SEPSIS_SUSPECTED, r.status());
    }

    // ── Pediatric ───────────────────────────────────────────────────

    @Test
    @DisplayName("Well infant (HR 120, RR 35, SBP 95) is NOT flagged — age-banded SIRS + no adult qSOFA")
    void pediatricWellInfantNotFlagged() {
        // Adult thresholds WOULD flag: HR>90 + RR>20 = SIRS 2, RR>=22 + SBP<=100 = qSOFA 2.
        // Age-banded infant: HR 120 (≤130 no), RR 35 (≤39 no) → SIRS 0; pediatric qSOFA RR/SBP not applied → 0.
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, 120, 35, 95, AvpuScore.ALERT), pediatricVisit(1));
        assertEquals(0, r.sirsScore());
        assertEquals(0, r.qsofaScore());
        assertEquals(SepsisStatus.NO_SEPSIS, r.status());
        assertTrue(r.pediatric());
        assertNotNull(r.pediatricCaveat()); // caveat always attached for children
    }

    @Test
    @DisplayName("Septic infant (HR 140, RR 45, temp 39) → age-banded SIRS 3 → SIRS_POSITIVE + caveat")
    void pediatricSepticInfant() {
        // Infant age-banded: temp 39 (✓), HR 140 (>130 ✓), RR 45 (>39 ✓) → SIRS 3.
        SepsisScreeningResult r = engine.screenForSepsis(vitals(39.0, 140, 45, 95, AvpuScore.ALERT), pediatricVisit(1));
        assertEquals(3, r.sirsScore());
        assertEquals(SepsisStatus.SIRS_POSITIVE, r.status());
        assertTrue(r.pediatric());
        assertNotNull(r.pediatricCaveat());
    }

    // ── Pediatric hypotension / shock (PALS 5th-percentile SBP for age) ──

    @Test
    @DisplayName("Hypotensive septic infant (6mo, SBP 55 < PALS 70) → SEPTIC_SHOCK + bundle")
    void pediatricInfantHypotensionIsShock() {
        // 6 months → infant bands (HR>130, RR>39) + PALS threshold 70 (1mo–<1y).
        // SIRS: temp 39.5 (✓), HR 160 (>130 ✓), RR 30 (≤39 no) → SIRS 2 → SIRS_POSITIVE.
        // SBP 55 < 70 → organ dysfunction → SEVERE_SEPSIS → 55 < 70 → SEPTIC_SHOCK.
        SepsisScreeningResult r = engine.screenForSepsis(
                vitals(39.5, 160, 30, 55, AvpuScore.ALERT), pediatricVisitMonths(6));
        assertEquals(SepsisStatus.SEPTIC_SHOCK, r.status());
        assertTrue(r.bundleRequired());
        assertTrue(r.systolicBpLow());   // persisted record must reflect pediatric hypotension
        assertTrue(r.pediatric());
        assertNotNull(r.pediatricCaveat());
    }

    @Test
    @DisplayName("Hypotensive neonate (0mo, SBP 55 < PALS 60) → SEPTIC_SHOCK")
    void pediatricNeonateHypotensionIsShock() {
        // <1 month → PALS threshold 60. SIRS: temp 39 (✓) + HR 170 (>130 ✓) → 2 → SIRS_POSITIVE.
        // SBP 55 < 60 → organ dysfunction → SEVERE → SEPTIC_SHOCK.
        SepsisScreeningResult r = engine.screenForSepsis(
                vitals(39.0, 170, 30, 55, AvpuScore.ALERT), pediatricVisitMonths(0));
        assertEquals(SepsisStatus.SEPTIC_SHOCK, r.status());
        assertTrue(r.bundleRequired());
    }

    @Test
    @DisplayName("Child just below PALS-for-age SBP (6y, SBP 78 < 70+12=82) → SEPTIC_SHOCK")
    void pediatricChildBelowThresholdIsShock() {
        // 6y → child bands (HR>99). PALS threshold = 70 + 6×2 = 82.
        // SIRS: temp 39 (✓) + HR 110 (>99 ✓) → 2 → SIRS_POSITIVE. SBP 78 < 82 → SEVERE → SHOCK.
        SepsisScreeningResult r = engine.screenForSepsis(
                vitals(39.0, 110, 18, 78, AvpuScore.ALERT), pediatricVisit(6));
        assertEquals(SepsisStatus.SEPTIC_SHOCK, r.status());
        assertTrue(r.systolicBpLow());
    }

    @Test
    @DisplayName("Child AT/above PALS-for-age SBP (6y, SBP 85 ≥ 82) → NOT hypotensive, stays SIRS_POSITIVE")
    void pediatricChildNormotensiveNotShock() {
        // Same child + SIRS 2, but SBP 85 ≥ 82 threshold → no BP organ dysfunction.
        // No infection/lactate at engine level → remains SIRS_POSITIVE (not SEVERE/SHOCK).
        SepsisScreeningResult r = engine.screenForSepsis(
                vitals(39.0, 110, 18, 85, AvpuScore.ALERT), pediatricVisit(6));
        assertEquals(SepsisStatus.SIRS_POSITIVE, r.status());
        assertFalse(r.systolicBpLow());
        assertFalse(r.bundleRequired());
    }

    // ── Missing data ────────────────────────────────────────────────

    @Test
    @DisplayName("Sparse vitals (only temperature) → INSUFFICIENT_DATA flagged, negative not reassuring")
    void insufficientData() {
        // Only temperature present (37); HR/RR/SBP/mentation all null → present=1 (<3).
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, null, null, null, null), adultVisit());
        assertTrue(r.insufficientData());
        assertNotNull(r.dataQualityNote());
    }

    @Test
    @DisplayName("Three of five core vitals present → NOT flagged insufficient")
    void sufficientDataAtThreshold() {
        SepsisScreeningResult r = engine.screenForSepsis(vitals(37.0, 80, 16, null, null), adultVisit());
        assertFalse(r.insufficientData());
    }
}
