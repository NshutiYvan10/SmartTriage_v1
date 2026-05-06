package com.smartTriage.smartTriage_server.module.triage.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates that {@link PediatricTewsCalculator} scores against the
 * correct KFH triage form (Infant 0–3 vs Child 3–12) based on age.
 * Each test asserts a single scalar score against the form's
 * column boundaries.
 */
class PediatricTewsCalculatorTest {

    private final PediatricTewsCalculator calc = new PediatricTewsCalculator();

    // ── INFANT (0–3) — Respiratory Rate ────────────────────────

    @Test void infantRR_under20_scores2() { assertEquals(2, calc.scoreInfantRespiratoryRate(19)); }
    @Test void infantRR_20to25_scores1()  { assertEquals(1, calc.scoreInfantRespiratoryRate(22)); }
    @Test void infantRR_normal_26to39()    { assertEquals(0, calc.scoreInfantRespiratoryRate(35)); }
    @Test void infantRR_40to49_scores1()  { assertEquals(1, calc.scoreInfantRespiratoryRate(45)); }
    @Test void infantRR_50plus_scores2()  { assertEquals(2, calc.scoreInfantRespiratoryRate(60)); }

    // Boundaries
    @Test void infantRR_exactly20()  { assertEquals(1, calc.scoreInfantRespiratoryRate(20)); }
    @Test void infantRR_exactly26()  { assertEquals(0, calc.scoreInfantRespiratoryRate(26)); }
    @Test void infantRR_exactly39()  { assertEquals(0, calc.scoreInfantRespiratoryRate(39)); }
    @Test void infantRR_exactly40()  { assertEquals(1, calc.scoreInfantRespiratoryRate(40)); }
    @Test void infantRR_exactly49()  { assertEquals(1, calc.scoreInfantRespiratoryRate(49)); }
    @Test void infantRR_exactly50()  { assertEquals(2, calc.scoreInfantRespiratoryRate(50)); }

    // ── INFANT (0–3) — Heart Rate ──────────────────────────────

    @Test void infantHR_under70_scores2()  { assertEquals(2, calc.scoreInfantHeartRate(60)); }
    @Test void infantHR_70to79_scores1()   { assertEquals(1, calc.scoreInfantHeartRate(75)); }
    @Test void infantHR_normal_80to130()    { assertEquals(0, calc.scoreInfantHeartRate(120)); }
    @Test void infantHR_131to159_scores1() { assertEquals(1, calc.scoreInfantHeartRate(140)); }
    @Test void infantHR_160plus_scores2()  { assertEquals(2, calc.scoreInfantHeartRate(170)); }

    @Test void infantHR_exactly70()  { assertEquals(1, calc.scoreInfantHeartRate(70)); }
    @Test void infantHR_exactly80()  { assertEquals(0, calc.scoreInfantHeartRate(80)); }
    @Test void infantHR_exactly130() { assertEquals(0, calc.scoreInfantHeartRate(130)); }
    @Test void infantHR_exactly131() { assertEquals(1, calc.scoreInfantHeartRate(131)); }
    @Test void infantHR_exactly159() { assertEquals(1, calc.scoreInfantHeartRate(159)); }
    @Test void infantHR_exactly160() { assertEquals(2, calc.scoreInfantHeartRate(160)); }

    // ── CHILD (3–12) — Respiratory Rate ────────────────────────

    @Test void childRR_under15_scores2() { assertEquals(2, calc.scoreChildRespiratoryRate(12)); }
    @Test void childRR_15to16_scores1()  { assertEquals(1, calc.scoreChildRespiratoryRate(15)); }
    @Test void childRR_normal_17to21()    { assertEquals(0, calc.scoreChildRespiratoryRate(18)); }
    @Test void childRR_22to26_scores1()  { assertEquals(1, calc.scoreChildRespiratoryRate(24)); }
    @Test void childRR_27plus_scores2()  { assertEquals(2, calc.scoreChildRespiratoryRate(40)); }

    // ── CHILD (3–12) — Heart Rate ──────────────────────────────

    @Test void childHR_under60_scores2()  { assertEquals(2, calc.scoreChildHeartRate(50)); }
    @Test void childHR_60to79_scores1()   { assertEquals(1, calc.scoreChildHeartRate(70)); }
    @Test void childHR_normal_80to99()     { assertEquals(0, calc.scoreChildHeartRate(90)); }
    @Test void childHR_100to129_scores1() { assertEquals(1, calc.scoreChildHeartRate(115)); }
    @Test void childHR_130plus_scores2()  { assertEquals(2, calc.scoreChildHeartRate(140)); }

    // ── Temperature (shared, both extremes = +2) ───────────────

    @Test void temp_cold_scores2()    { assertEquals(2, calc.scoreTemperature(34.5)); }
    @Test void temp_normal_low()       { assertEquals(0, calc.scoreTemperature(35.0)); }
    @Test void temp_normal_high()      { assertEquals(0, calc.scoreTemperature(38.4)); }
    @Test void temp_hot_scores2()     { assertEquals(2, calc.scoreTemperature(39.0)); }
    // Cold/Hot scoring is symmetric — this is the regression guard
    // against the previous frontend bug where Hot was scored as +1.
    @Test void temp_cold_and_hot_symmetric() {
        assertEquals(calc.scoreTemperature(34.0), calc.scoreTemperature(40.0));
    }

    // ── AVPU — Confused dropped for infants ────────────────────

    @Test void avpu_confused_infant_clampsToAlert() {
        // Infant form has no Confused column; clamp to 0.
        assertEquals(0, calc.scoreAvpu(AvpuScore.CONFUSED, true));
    }
    @Test void avpu_confused_child_scores1() {
        assertEquals(1, calc.scoreAvpu(AvpuScore.CONFUSED, false));
    }
    @Test void avpu_alert_zero_infant() { assertEquals(0, calc.scoreAvpu(AvpuScore.ALERT, true)); }
    @Test void avpu_alert_zero_child()  { assertEquals(0, calc.scoreAvpu(AvpuScore.ALERT, false)); }

    // ── Routing: full-flow tests with realistic vitals ─────────

    @Test
    void normalInfant_scoresZero() {
        // 1-year-old, RR 35, HR 120, Temp 37, Alert, no trauma.
        // All should be in the "normal for age" band on the Infant
        // form — total TEWS = 0. This is the regression guard for
        // the original bug where infants were scored against the
        // 3-12 grid and a normal HR=120 would score +1 / +2.
        VitalSigns v = new VitalSigns();
        v.setRespiratoryRate(35);
        v.setHeartRate(120);
        v.setTemperature(37.0);
        int total = calc.calculatePediatricTewsScore(
                12, v, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        assertEquals(0, total);
    }

    @Test
    void normalInfant_underOldGrid_wouldOverScore() {
        // 6-month-old with RR=40 (normal infant rate) and HR=140
        // (normal infant rate). Under the old (always-child)
        // implementation this would score TEWS 4 (+1 RR 40-49 was
        // wrong, +2 HR ≥130, +1 RR 22-26 was wrong) — but actually
        // under child grid: RR=40 → 27+ → +2; HR=140 → 130+ → +2;
        // total = 4. Under correct infant grid: RR=40 → 40-49 → +1;
        // HR=140 → 131-159 → +1; total = 2. The infant scoring
        // saves this child from a falsely high triage category.
        VitalSigns v = new VitalSigns();
        v.setRespiratoryRate(40);
        v.setHeartRate(140);
        v.setTemperature(37.0);
        int total = calc.calculatePediatricTewsScore(
                6, v, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        assertEquals(2, total);
    }

    @Test
    void bradycardicInfant_scoresHigh() {
        // 1-year-old with HR=60 (severe bradycardia for an infant —
        // peri-arrest sign). Under the old (always-child)
        // implementation HR=60 → 60-79 → +1 (false reassurance).
        // Under correct infant grid: HR=60 → <70 → +2.
        VitalSigns v = new VitalSigns();
        v.setRespiratoryRate(35);
        v.setHeartRate(60);
        v.setTemperature(37.0);
        int total = calc.calculatePediatricTewsScore(
                12, v, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        assertEquals(2, total);
    }

    @Test
    void normalChild_scoresZero() {
        // 8-year-old, RR 18, HR 90, Temp 37, Alert.
        VitalSigns v = new VitalSigns();
        v.setRespiratoryRate(18);
        v.setHeartRate(90);
        v.setTemperature(37.0);
        int total = calc.calculatePediatricTewsScore(
                96, v, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        assertEquals(0, total);
    }

    @Test
    void boundaryAt36Months_usesChildGrid() {
        // Exactly 36 months → CHILD form. RR=35 should score +2
        // (≥27 on child grid). On infant grid it would be 0.
        VitalSigns v = new VitalSigns();
        v.setRespiratoryRate(35);
        v.setHeartRate(90);
        v.setTemperature(37.0);
        int total = calc.calculatePediatricTewsScore(
                36, v, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        assertEquals(2, total);
    }

    @Test
    void justUnder36Months_usesInfantGrid() {
        // 35 months → INFANT form. RR=35 → 26-39 → 0.
        VitalSigns v = new VitalSigns();
        v.setRespiratoryRate(35);
        v.setHeartRate(90);
        v.setTemperature(37.0);
        int total = calc.calculatePediatricTewsScore(
                35, v, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        assertEquals(0, total);
    }

    @Test
    void nullVitals_scoresZero() {
        // Defensive: null vitals shouldn't crash; just contribute 0.
        int total = calc.calculatePediatricTewsScore(
                12, null, MobilityStatus.WALKING, AvpuScore.ALERT, TraumaStatus.NO_TRAUMA);
        assertEquals(0, total);
    }
}
