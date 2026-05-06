package com.smartTriage.smartTriage_server.module.triage.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pediatric TEWS Calculator — King Faisal Hospital triage forms.
 *
 * <p>Two distinct grids per the official KFH triage forms:
 *
 * <h3>INFANT (0–3 years)</h3>
 * <pre>
 * | Parameter  | 3  | 2           | 1          | 0          | 1       | 2                 | 3           |
 * |------------|----|-------------|------------|------------|---------|-------------------|-------------|
 * | Mobility   |    |             |            | Normal for |         | Unable to move    |             |
 * |            |    |             |            | age        |         | normally          |             |
 * | RR         |    | Less than 20| 20–25      | 26–39      | 40–49   | 50 or more        |             |
 * | P (Pulse)  |    | Less than 70| 70–79      | 80–130     | 131–159 | 160 or more       |             |
 * | Temp       |    |             | Cold/&lt;35|  35–38.4   |         | Hot/≥38.4         |             |
 * | AVPU       |    |             |            | Alert      | Voice   | Pain              | Unresponsive|
 * | Trauma     |    |             |            | No         | Yes     |                   |             |
 * </pre>
 *
 * <h3>CHILD (3–12 years)</h3>
 * <pre>
 * | Parameter  | 3  | 2           | 1          | 0          | 1       | 2                 | 3           |
 * |------------|----|-------------|------------|------------|---------|-------------------|-------------|
 * | Mobility   |    |             |            | Normal for |         | Unable to walk    |             |
 * |            |    |             |            | age        |         | as normal         |             |
 * | RR         |    | Less than 15| 15–16      | 17–21      | 22–26   | 27 or more        |             |
 * | P (Pulse)  |    | Less than 60| 60–79      | 80–99      | 100–129 | 130 or more       |             |
 * | Temp       |    |             | Cold/&lt;35|  35–38.4   |         | Hot/≥38.4         |             |
 * | AVPU       |    |             | Confused   | Alert      | Voice   | Pain              | Unresponsive|
 * | Trauma     |    |             |            | No         | Yes     |                   |             |
 * </pre>
 *
 * <p>The two grids are NOT interchangeable. A 1-year-old's normal HR is
 * 100–160; a 10-year-old's normal HR is 70–110. Scoring an infant against
 * the child grid yields false high-acuity TEWS for healthy infants and
 * masks bradycardia in critically ill infants — both directions are
 * patient-safety failures.
 *
 * <p>Boundary at 36 months (3 years). The KFH forms overlap at 3 years
 * exactly; this implementation routes &lt;36 months → INFANT,
 * ≥36 months → CHILD per project decision.
 *
 * <p>SBP is NOT a scored TEWS component on either KFH peds form.
 */
@Slf4j
@Component
public class PediatricTewsCalculator {

    /** Infant/child boundary in months — strictly &lt; 36 → infant. */
    public static final int INFANT_AGE_BOUNDARY_MONTHS = 36;

    /**
     * Calculate TEWS for a pediatric patient using the age-appropriate grid.
     *
     * @param ageInMonths   the patient's age in months at triage time
     * @param vitals        recorded vital signs (nullable if emergency bypass)
     * @param mobility      mobility status
     * @param avpu          AVPU consciousness scale
     * @param traumaStatus  trauma present or not
     * @return              TEWS score (0–18). The categorisation thresholds
     *                      live in {@code RwandaPediatricTriageDecisionEngine}.
     */
    public int calculatePediatricTewsScore(
            int ageInMonths,
            VitalSigns vitals,
            MobilityStatus mobility,
            AvpuScore avpu,
            TraumaStatus traumaStatus
    ) {
        boolean isInfant = ageInMonths < INFANT_AGE_BOUNDARY_MONTHS;

        int mobilityScore = mobility != null ? mobility.getTewsPoints() : 0;
        // Infant form has no "Confused" column — clamp Confused to Alert (0)
        // so a misclick in the form's enum doesn't accidentally over-score
        // an infant whose AVPU enum was set to CONFUSED.
        int avpuScore = scoreAvpu(avpu, isInfant);
        int traumaScore = traumaStatus != null ? traumaStatus.getTewsPoints() : 0;

        int rrScore = 0, hrScore = 0, tempScore = 0;
        if (vitals != null) {
            rrScore = isInfant
                    ? scoreInfantRespiratoryRate(vitals.getRespiratoryRate())
                    : scoreChildRespiratoryRate(vitals.getRespiratoryRate());
            hrScore = isInfant
                    ? scoreInfantHeartRate(vitals.getHeartRate())
                    : scoreChildHeartRate(vitals.getHeartRate());
            tempScore = scoreTemperature(vitals.getTemperature());
        }

        int total = mobilityScore + avpuScore + traumaScore
                + rrScore + hrScore + tempScore;

        log.debug("Pediatric TEWS [{}]: total={} ageMonths={} [Mob:{} RR:{} HR:{} Temp:{} AVPU:{} Trauma:{}]",
                isInfant ? "INFANT 0-3" : "CHILD 3-12",
                total, ageInMonths,
                mobilityScore, rrScore, hrScore, tempScore, avpuScore, traumaScore);

        return total;
    }

    /**
     * Backwards-compat overload — assumes a 3-12 year-old patient when
     * the caller hasn't yet been ported to pass age. Logs a WARNING so
     * the gap is visible during the rollout. Once all callers thread
     * ageInMonths through, this overload should be removed.
     */
    public int calculatePediatricTewsScore(
            VitalSigns vitals,
            MobilityStatus mobility,
            AvpuScore avpu,
            TraumaStatus traumaStatus
    ) {
        log.warn("[peds-tews] called without ageInMonths — defaulting to CHILD (3-12) grid. "
                + "This loses age-appropriate scoring for infants <3y. Caller should pass ageInMonths.");
        return calculatePediatricTewsScore(
                INFANT_AGE_BOUNDARY_MONTHS, vitals, mobility, avpu, traumaStatus);
    }

    // ── INFANT (0–3 years) thresholds ────────────────────────────

    /**
     * INFANT RR scoring per KFH Infant Triage Form (0–3):
     *   &lt; 20  → 2
     *   20–25 → 1
     *   26–39 → 0  (normal for age)
     *   40–49 → 1
     *   ≥ 50  → 2
     */
    int scoreInfantRespiratoryRate(Integer rr) {
        if (rr == null) return 0;
        if (rr < 20)  return 2;
        if (rr <= 25) return 1;
        if (rr <= 39) return 0;
        if (rr <= 49) return 1;
        return 2; // ≥ 50
    }

    /**
     * INFANT HR scoring per KFH Infant Triage Form (0–3):
     *   &lt; 70   → 2
     *   70–79  → 1
     *   80–130 → 0  (normal for age — wide range covers neonate→toddler)
     *   131–159 → 1
     *   ≥ 160  → 2
     */
    int scoreInfantHeartRate(Integer hr) {
        if (hr == null) return 0;
        if (hr < 70)   return 2;
        if (hr <= 79)  return 1;
        if (hr <= 130) return 0;
        if (hr <= 159) return 1;
        return 2; // ≥ 160
    }

    // ── CHILD (3–12 years) thresholds ────────────────────────────

    /**
     * CHILD RR scoring per KFH Child Triage Form (3–12):
     *   &lt; 15  → 2
     *   15–16 → 1
     *   17–21 → 0  (normal for age)
     *   22–26 → 1
     *   ≥ 27  → 2
     */
    int scoreChildRespiratoryRate(Integer rr) {
        if (rr == null) return 0;
        if (rr < 15)  return 2;
        if (rr <= 16) return 1;
        if (rr <= 21) return 0;
        if (rr <= 26) return 1;
        return 2; // ≥ 27
    }

    /**
     * CHILD HR scoring per KFH Child Triage Form (3–12):
     *   &lt; 60   → 2
     *   60–79  → 1
     *   80–99  → 0  (normal for age)
     *   100–129 → 1
     *   ≥ 130  → 2
     */
    int scoreChildHeartRate(Integer hr) {
        if (hr == null) return 0;
        if (hr < 60)   return 2;
        if (hr <= 79)  return 1;
        if (hr <= 99)  return 0;
        if (hr <= 129) return 1;
        return 2; // ≥ 130
    }

    // ── Shared (both age bands) ──────────────────────────────────

    /**
     * Temperature scoring — same on both KFH peds forms:
     *   &lt; 35    → 2  (Cold; left-side score-2 column)
     *   35–38.4 → 0  (normal)
     *   &gt; 38.4 → 2  (Hot; right-side score-2 column)
     *
     * <p>Both extremes score +2. Hypothermia in a sick child is at
     * least as dangerous as fever — sepsis-associated cold shock is a
     * common cause of paeds death in LMIC EDs and must not be
     * under-scored.
     */
    int scoreTemperature(Double temp) {
        if (temp == null) return 0;
        if (temp < 35.0)  return 2;
        if (temp <= 38.4) return 0;
        return 2; // > 38.4
    }

    /**
     * AVPU scoring. CHILD form has Confused = +1; INFANT form has no
     * Confused column. We clamp Confused → 0 for infants so a misclick
     * in the enum doesn't introduce a score that the form doesn't
     * acknowledge.
     */
    int scoreAvpu(AvpuScore avpu, boolean isInfant) {
        if (avpu == null) return 0;
        // Confused is not on the infant form — score as Alert.
        if (isInfant && avpu == AvpuScore.CONFUSED) return 0;
        return avpu.getTewsPoints();
    }
}
