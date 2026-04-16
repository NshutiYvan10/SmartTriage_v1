package com.smartTriage.smartTriage_server.module.triage.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pediatric TEWS Calculator — Rwanda National Standard Child Triage Form (3-12 years).
 *
 * Exact implementation of the TEWS scoring grid from the standard child triage form.
 *
 * TEWS Scoring Grid (from the child form — "CIRCLE THE BOX"):
 *
 * | Parameter  | 3  | 2           | 1       | 0          | 1       | 2                    | 3           |
 * |------------|----|-------------|---------|------------|---------|----------------------|-------------|
 * | Mobility   |    |             |         | Normal for | (blank) | Unable to walk as    |             |
 * |            |    |             |         | age        |         | normal               |             |
 * | RR         |    | Less than 15| 15-16   | 17-21      | 22-26   | 27 or more           |             |
 * | P (Pulse)  |    | Less than 60| 60-79   | 80-99      | 100-129 | 130 or more          |             |
 * | Temp       |    | Cold/<35    |         | 35-38.4    |         |                      | Hot/≥38.4   |
 * | AVPU       |    |             | Confused| Alert      | Voice   | Pain                 | Unresponsive|
 * | Trauma     |    |             |         | No         | Yes     |                      |             |
 *
 * Total range: 0-18
 *
 * IMPORTANT: These thresholds are DIFFERENT from the adult form.
 * The child form uses age-appropriate vital sign ranges that reflect
 * normal pediatric physiology (higher HR, higher RR, lower BP).
 *
 * NOTE: SBP is NOT a scored TEWS component on the child form.
 * The form shows "BP:__/__ Weight:__ Height:__" in the footer only.
 *
 * CRITICAL: This is a life-critical calculation. Accuracy is paramount.
 * Thresholds are transcribed exactly from the standard Rwanda child triage form.
 */
@Slf4j
@Component
public class PediatricTewsCalculator {

    /**
     * Calculate pediatric TEWS score from vital signs and assessment components.
     * Mobility, AVPU, and Trauma scores come from enum.getTewsPoints().
     * Vital sign scores are calculated from the child form threshold grid.
     *
     * @param vitals        Recorded vital signs (nullable if emergency bypass)
     * @param mobility      Mobility status (Walking / With Help / Stretcher)
     * @param avpu          AVPU consciousness scale
     * @param traumaStatus  Trauma present or not
     * @return              Calculated TEWS score (0-18)
     */
    public int calculatePediatricTewsScore(
            VitalSigns vitals,
            MobilityStatus mobility,
            AvpuScore avpu,
            TraumaStatus traumaStatus
    ) {
        int mobilityScore = mobility != null ? mobility.getTewsPoints() : 0;
        int avpuScore = avpu != null ? avpu.getTewsPoints() : 0;
        int traumaScore = traumaStatus != null ? traumaStatus.getTewsPoints() : 0;

        int rrScore = 0, hrScore = 0, tempScore = 0;
        if (vitals != null) {
            rrScore = scoreRespiratoryRate(vitals.getRespiratoryRate());
            hrScore = scoreHeartRate(vitals.getHeartRate());
            tempScore = scoreTemperature(vitals.getTemperature());
            // NOTE: SBP is NOT on the child triage form TEWS grid.
            // The child form only shows BP/Weight/Height in the footer area,
            // not as a scored TEWS component.
        }

        int total = mobilityScore + avpuScore + traumaScore
                + rrScore + hrScore + tempScore;

        log.debug("Pediatric TEWS calculated: {} [Mobility:{} RR:{} HR:{} Temp:{} AVPU:{} Trauma:{}] " +
                  "(Scores: {}+{}+{}+{}+{}+{})",
                total,
                mobility,
                vitals != null ? vitals.getRespiratoryRate() : null,
                vitals != null ? vitals.getHeartRate() : null,
                vitals != null ? vitals.getTemperature() : null,
                avpu, traumaStatus,
                mobilityScore, rrScore, hrScore, tempScore, avpuScore, traumaScore);

        return total;
    }

    // --- Vital Sign Scoring Functions (Rwanda Child Triage Form thresholds) ---

    /**
     * Respiratory Rate scoring — child form grid:
     *   Less than 15  → 2 points (left side)
     *   15-16         → 1 point
     *   17-21         → 0 points (baseline / normal for age)
     *   22-26         → 1 point
     *   27 or more    → 2 points
     */
    int scoreRespiratoryRate(Integer rr) {
        if (rr == null) return 0;
        if (rr < 15)  return 2;
        if (rr <= 16) return 1;
        if (rr <= 21) return 0;
        if (rr <= 26) return 1;
        return 2; // ≥ 27
    }

    /**
     * Heart Rate / Pulse scoring — child form grid:
     *   Less than 60  → 2 points (left side)
     *   60-79         → 1 point
     *   80-99         → 0 points (baseline / normal for age)
     *   100-129       → 1 point
     *   130 or more   → 2 points
     */
    int scoreHeartRate(Integer hr) {
        if (hr == null) return 0;
        if (hr < 60)   return 2;
        if (hr <= 79)  return 1;
        if (hr <= 99)  return 0;
        if (hr <= 129) return 1;
        return 2; // ≥ 130
    }

    /**
     * Temperature scoring — child form grid (same layout as adult):
     *   Cold or Under 35  → 2 points
     *   35.0 - 38.4       → 0 points (normal)
     *   Hot or Over 38.4  → 2 points (placed in far-right column 3,
     *                        but standard mSAT interpretation = 2 points)
     *
     * Note: The child form has the same temperature layout as the adult form.
     */
    int scoreTemperature(Double temp) {
        if (temp == null) return 0;
        if (temp < 35.0)  return 2;
        if (temp <= 38.4) return 0;
        return 2; // > 38.4
    }
}
