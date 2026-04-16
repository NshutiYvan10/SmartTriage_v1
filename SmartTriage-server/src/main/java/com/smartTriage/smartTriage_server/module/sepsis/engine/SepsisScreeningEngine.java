package com.smartTriage.smartTriage_server.module.sepsis.engine;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SepsisScreeningEngine — implements qSOFA and SIRS scoring for sepsis detection.
 *
 * Scoring criteria:
 *
 * qSOFA (Quick Sequential Organ Failure Assessment):
 *   - Altered mentation (AVPU != ALERT, or GCS < 15): +1
 *   - Respiratory rate >= 22: +1
 *   - Systolic BP <= 100: +1
 *   Score >= 2 → SEPSIS_SUSPECTED
 *
 * SIRS (Systemic Inflammatory Response Syndrome):
 *   - Temperature > 38°C or < 36°C: +1
 *   - Heart rate > 90 bpm: +1
 *   - Respiratory rate > 20 breaths/min: +1
 *   - WBC > 12,000 or < 4,000 or > 10% bands: +1
 *   Score >= 2 with suspected infection → SEPSIS_SUSPECTED
 *
 * Escalation:
 *   - With organ dysfunction (SBP < 90 or lactate > 2) → SEVERE_SEPSIS
 *   - With persistent hypotension (SBP < 90 despite fluids) → SEPTIC_SHOCK
 *
 * Based on Rwanda MoH sepsis management guidelines and Surviving Sepsis Campaign
 * adapted for resource-limited settings.
 */
@Slf4j
@Component
public class SepsisScreeningEngine {

    /**
     * Result record for a sepsis screening.
     */
    public record SepsisScreeningResult(
            SepsisStatus status,
            int qsofaScore,
            int sirsScore,
            boolean alteredMentation,
            boolean respiratoryRateHigh,
            boolean systolicBpLow,
            boolean temperatureCriteriaMet,
            boolean heartRateCriteriaMet,
            boolean respiratoryRateCriteriaMet,
            boolean wbcCriteriaMet,
            List<String> findings,
            boolean bundleRequired
    ) {}

    /**
     * Screen a patient for sepsis using their latest vital signs.
     *
     * @param vitals the patient's current vital signs
     * @param visit  the patient's visit (for context like suspected infection)
     * @return SepsisScreeningResult with status, scores, and findings
     */
    public SepsisScreeningResult screenForSepsis(VitalSigns vitals, Visit visit) {
        List<String> findings = new ArrayList<>();

        // ================================================================
        // qSOFA SCORING
        // ================================================================
        boolean alteredMentation = false;
        boolean respiratoryRateHigh = false;
        boolean systolicBpLow = false;
        int qsofaScore = 0;

        // Altered mentation: AVPU != ALERT or GCS < 15
        if (vitals.getAvpu() != null && vitals.getAvpu() != AvpuScore.ALERT) {
            alteredMentation = true;
            qsofaScore++;
            findings.add("Altered mentation detected (AVPU: " + vitals.getAvpu().getDescription() + ")");
        } else if (vitals.getGcsScore() != null && vitals.getGcsScore() < 15) {
            alteredMentation = true;
            qsofaScore++;
            findings.add("Altered mentation detected (GCS: " + vitals.getGcsScore() + ")");
        }

        // Respiratory rate >= 22
        if (vitals.getRespiratoryRate() != null && vitals.getRespiratoryRate() >= 22) {
            respiratoryRateHigh = true;
            qsofaScore++;
            findings.add("Respiratory rate elevated: " + vitals.getRespiratoryRate() + " (>= 22)");
        }

        // Systolic BP <= 100
        if (vitals.getSystolicBp() != null && vitals.getSystolicBp() <= 100) {
            systolicBpLow = true;
            qsofaScore++;
            findings.add("Systolic BP low: " + vitals.getSystolicBp() + " mmHg (<= 100)");
        }

        // ================================================================
        // SIRS SCORING
        // ================================================================
        boolean temperatureCriteriaMet = false;
        boolean heartRateCriteriaMet = false;
        boolean respiratoryRateCriteriaMet = false;
        boolean wbcCriteriaMet = false;
        int sirsScore = 0;

        // Temperature > 38°C or < 36°C
        if (vitals.getTemperature() != null
                && (vitals.getTemperature() > 38.0 || vitals.getTemperature() < 36.0)) {
            temperatureCriteriaMet = true;
            sirsScore++;
            findings.add("Temperature abnormal: " + vitals.getTemperature() + "°C (>38 or <36)");
        }

        // Heart rate > 90
        if (vitals.getHeartRate() != null && vitals.getHeartRate() > 90) {
            heartRateCriteriaMet = true;
            sirsScore++;
            findings.add("Heart rate elevated: " + vitals.getHeartRate() + " bpm (>90)");
        }

        // Respiratory rate > 20
        if (vitals.getRespiratoryRate() != null && vitals.getRespiratoryRate() > 20) {
            respiratoryRateCriteriaMet = true;
            sirsScore++;
            findings.add("Respiratory rate elevated for SIRS: " + vitals.getRespiratoryRate() + " (>20)");
        }

        // WBC criteria — note: WBC is typically from lab investigations, not vitals.
        // This will be false unless lab data is integrated into the screening.
        // Placeholder for future integration.

        // ================================================================
        // STATUS DETERMINATION
        // ================================================================
        SepsisStatus status = SepsisStatus.NO_SEPSIS;

        // Check for SIRS positivity
        if (sirsScore >= 2) {
            status = SepsisStatus.SIRS_POSITIVE;
            findings.add("SIRS criteria met: " + sirsScore + "/4 criteria positive");
        }

        // qSOFA >= 2 → SEPSIS_SUSPECTED
        if (qsofaScore >= 2) {
            status = SepsisStatus.SEPSIS_SUSPECTED;
            findings.add("qSOFA score >= 2: SEPSIS SUSPECTED");
        }

        // SIRS >= 2 with suspected infection → also SEPSIS_SUSPECTED
        if (sirsScore >= 2 && status == SepsisStatus.SIRS_POSITIVE) {
            // If there's clinical context suggesting infection, escalate
            // The service layer will check for suspected infection source
            // For the engine, SIRS >= 2 already indicates potential sepsis
        }

        // Check for organ dysfunction → SEVERE_SEPSIS
        if (status == SepsisStatus.SEPSIS_SUSPECTED || status == SepsisStatus.SIRS_POSITIVE) {
            boolean hasOrganDysfunction = false;

            if (vitals.getSystolicBp() != null && vitals.getSystolicBp() < 90) {
                hasOrganDysfunction = true;
                findings.add("Organ dysfunction: SBP < 90 mmHg (hypotension)");
            }

            // Lactate > 2 mmol/L is checked at the service level (from screening request)

            if (hasOrganDysfunction) {
                status = SepsisStatus.SEVERE_SEPSIS;
                findings.add("SEVERE SEPSIS: Sepsis with organ dysfunction");
            }
        }

        // Persistent hypotension (SBP < 90) → SEPTIC_SHOCK
        // In a full implementation, this would check if hypotension persists after fluid resuscitation.
        // Here we flag it based on very low SBP as a conservative measure.
        if (status == SepsisStatus.SEVERE_SEPSIS
                && vitals.getSystolicBp() != null && vitals.getSystolicBp() < 70) {
            status = SepsisStatus.SEPTIC_SHOCK;
            findings.add("SEPTIC SHOCK: Persistent severe hypotension (SBP < 70 mmHg)");
        }

        boolean bundleRequired = status == SepsisStatus.SEPSIS_SUSPECTED
                || status == SepsisStatus.SEVERE_SEPSIS
                || status == SepsisStatus.SEPTIC_SHOCK;

        if (bundleRequired) {
            findings.add("1-HOUR SEPSIS BUNDLE REQUIRED — initiate immediately");
        }

        log.info("Sepsis screening for Visit {}: Status={}, qSOFA={}, SIRS={}, Bundle={}",
                visit.getVisitNumber(), status, qsofaScore, sirsScore, bundleRequired);

        return new SepsisScreeningResult(
                status, qsofaScore, sirsScore,
                alteredMentation, respiratoryRateHigh, systolicBpLow,
                temperatureCriteriaMet, heartRateCriteriaMet, respiratoryRateCriteriaMet, wbcCriteriaMet,
                findings, bundleRequired
        );
    }
}
