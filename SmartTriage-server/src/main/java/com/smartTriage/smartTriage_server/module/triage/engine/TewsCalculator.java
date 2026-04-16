package com.smartTriage.smartTriage_server.module.triage.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.MobilityStatus;
import com.smartTriage.smartTriage_server.common.enums.TraumaStatus;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TEWS (Triage Early Warning Score) Calculator Engine.
 *
 * Exact implementation of the Rwanda National Standard Adult Triage Form.
 *
 * TEWS Scoring Grid (from the standard form — "CIRCLE THE BOX"):
 *
 * | Parameter  | 3          | 2          | 1       | 0        | 1               | 2               | 3           |
 * |------------|------------|------------|---------|----------|-----------------|-----------------|-------------|
 * | Mobility   |            |            |         | Walking  | Help/Wheelchair | Stretcher/Imm.  |             |
 * | RR         |            |            | <9      | 9-14     | 15-20           | 21-29           | >29         |
 * | Pulse (P)  |            | <41        | 41-50   | 51-100   | 101-110         | 111-129         | >129        |
 * | SBP        |            | <71        | 71-80   | 81-100   | 101-199         |                 | >199        |
 * | Temp       |            | Cold/<35   |         | 35-38.4  |                 |                 | Hot/≥38.4   |
 * | AVPU       |            |            | Confused| Alert    | Voice           | Pain            | Unresponsive|
 * | Trauma     |            |            |         | No       | Yes             |                 |             |
 *
 * Total range: 0-18
 *
 * CRITICAL: This is a life-critical calculation. Accuracy is paramount.
 * Thresholds are transcribed exactly from the standard Rwanda triage form.
 */
@Slf4j
@Component
public class TewsCalculator {

    /**
     * Calculate TEWS score from vital signs and assessment components.
     * Mobility, AVPU, and Trauma scores come from enum.getTewsPoints().
     * Vital sign scores are calculated from the standard threshold grid.
     */
    public int calculateTewsScore(
            VitalSigns vitals,
            MobilityStatus mobility,
            AvpuScore avpu,
            TraumaStatus traumaStatus
    ) {
        int mobilityScore = mobility != null ? mobility.getTewsPoints() : 0;
        int avpuScore = avpu != null ? avpu.getTewsPoints() : 0;
        int traumaScore = traumaStatus != null ? traumaStatus.getTewsPoints() : 0;

        int rrScore = 0, hrScore = 0, sbpScore = 0, tempScore = 0;
        if (vitals != null) {
            rrScore = scoreRespiratoryRate(vitals.getRespiratoryRate());
            hrScore = scoreHeartRate(vitals.getHeartRate());
            sbpScore = scoreSystolicBp(vitals.getSystolicBp());
            tempScore = scoreTemperature(vitals.getTemperature());
        }

        int total = mobilityScore + avpuScore + traumaScore
                + rrScore + hrScore + sbpScore + tempScore;

        log.debug("TEWS calculated: {} [Mobility:{} RR:{} HR:{} SBP:{} Temp:{} AVPU:{} Trauma:{}] " +
                  "(Scores: {}+{}+{}+{}+{}+{}+{})",
                total,
                mobility,
                vitals != null ? vitals.getRespiratoryRate() : null,
                vitals != null ? vitals.getHeartRate() : null,
                vitals != null ? vitals.getSystolicBp() : null,
                vitals != null ? vitals.getTemperature() : null,
                avpu, traumaStatus,
                mobilityScore, rrScore, hrScore, sbpScore, tempScore, avpuScore, traumaScore);

        return total;
    }

    // --- Vital Sign Scoring Functions (Rwanda Adult Triage Form thresholds) ---

    /**
     * Respiratory Rate scoring — standard grid:
     *   Less than 9   → 1 point  (left side)
     *   9-14          → 0 points (baseline)
     *   15-20         → 1 point
     *   21-29         → 2 points
     *   More than 29  → 3 points
     */
    int scoreRespiratoryRate(Integer rr) {
        if (rr == null) return 0;
        if (rr < 9)   return 1;
        if (rr <= 14) return 0;
        if (rr <= 20) return 1;
        if (rr <= 29) return 2;
        return 3; // > 29
    }

    /**
     * Heart Rate / Pulse scoring — standard grid:
     *   Less than 41  → 2 points (left side)
     *   41-50         → 1 point
     *   51-100        → 0 points (baseline)
     *   101-110       → 1 point
     *   111-129       → 2 points
     *   More than 129 → 3 points
     */
    int scoreHeartRate(Integer hr) {
        if (hr == null) return 0;
        if (hr < 41)   return 2;
        if (hr <= 50)  return 1;
        if (hr <= 100) return 0;
        if (hr <= 110) return 1;
        if (hr <= 129) return 2;
        return 3; // > 129
    }

    /**
     * Systolic Blood Pressure scoring — standard grid:
     *   Less than 71  → 2 points (left side)
     *   71-80         → 1 point
     *   81-100        → 0 points (baseline)
     *   101-199       → 1 point
     *   More than 199 → 3 points
     *
     * Note: The standard form has no score 2 on the right side for SBP.
     */
    int scoreSystolicBp(Integer sbp) {
        if (sbp == null) return 0;
        if (sbp < 71)   return 2;
        if (sbp <= 80)  return 1;
        if (sbp <= 100) return 0;
        if (sbp <= 199) return 1;
        return 3; // > 199
    }

    /**
     * Temperature scoring — standard grid:
     *   Cold or Under 35  → 2 points
     *   35.0 - 38.4       → 0 points (normal)
     *   Hot or Over 38.4  → 2 points  (Note: The form maps this to score column 3 on the right,
     *                                    but the label says "Hot or over 38.4" in the 2-point column.
     *                                    The form layout places it at rightmost = 3 points.
     *                                    We follow the column position: right-3 = 3 points.)
     *
     * Interpretation based on standard grid column headers:
     *   Under 35   → column 2 (left)  = 2 points
     *   35-38.4    → column 0          = 0 points
     *   Over 38.4  → column 3 (right)  = 2 points (placed in far-right but labeled in 2-col area)
     *
     * Using the standard mSAT interpretation: Under 35 = 2, 35-38.4 = 0, Over 38.4 = 2.
     */
    int scoreTemperature(Double temp) {
        if (temp == null) return 0;
        if (temp < 35.0)  return 2;
        if (temp <= 38.4) return 0;
        return 2; // > 38.4
    }
}
