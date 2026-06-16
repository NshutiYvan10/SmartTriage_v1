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
            boolean bundleRequired,
            boolean pediatric,
            String pediatricCaveat,
            boolean insufficientData,
            String dataQualityNote
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

        // Age-band the SIRS vital thresholds. Adult cutoffs badly misfit children:
        // a well infant's normal HR (80-130) and RR (26-39) both exceed the adult
        // SIRS HR>90 / RR>20, so adult thresholds over-screen well babies. We use
        // age-appropriate "above normal for age" cutoffs grounded in the same norms
        // the pediatric triage calculator uses (infant < 3y, child 3-12y).
        boolean pediatric = false;
        int ageMonths = -1;         // resolved below for pediatric patients
        int sirsHrThreshold = 90;   // adult: HR > 90
        int sirsRrThreshold = 20;   // adult: RR > 20
        try {
            pediatric = visit.isPediatric();
            if (pediatric) {
                ageMonths = resolveAgeMonths(visit);
                if (ageMonths < INFANT_AGE_BOUNDARY_MONTHS) {   // infant (< 3y)
                    sirsHrThreshold = 130;
                    sirsRrThreshold = 39;
                } else {                                        // child 3-12y
                    sirsHrThreshold = 99;
                    sirsRrThreshold = 26;
                }
            }
        } catch (Exception ignored) {
            // fall back to adult thresholds if age can't be resolved
        }
        // PALS 5th-percentile systolic-BP hypotension cutoff for this child's age
        // (mmHg). -1 (never trips) for adults / unresolved age. Computed once and
        // reused by the organ-dysfunction and septic-shock blocks below so a
        // hypotensive child can finally reach SEVERE_SEPSIS / SEPTIC_SHOCK.
        int pedHypotensionSbp = pediatric ? pediatricHypotensionThreshold(ageMonths) : -1;

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

        // Respiratory rate >= 22 (adult qSOFA only — the adult RR/SBP qSOFA
        // thresholds are NOT validated in children, so for pediatric patients we
        // do not apply them; pediatric suspicion is driven by age-banded SIRS +
        // the mandatory caveat, avoiding adult-threshold false positives).
        if (!pediatric && vitals.getRespiratoryRate() != null && vitals.getRespiratoryRate() >= 22) {
            respiratoryRateHigh = true;
            qsofaScore++;
            findings.add("Respiratory rate elevated: " + vitals.getRespiratoryRate() + " (>= 22)");
        }

        // Systolic BP <= 100 (adult qSOFA only — see note above)
        if (!pediatric && vitals.getSystolicBp() != null && vitals.getSystolicBp() <= 100) {
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

        // Heart rate above the age-appropriate threshold
        if (vitals.getHeartRate() != null && vitals.getHeartRate() > sirsHrThreshold) {
            heartRateCriteriaMet = true;
            sirsScore++;
            findings.add("Heart rate elevated: " + vitals.getHeartRate() + " bpm (>" + sirsHrThreshold
                    + (pediatric ? ", age-adjusted)" : ")"));
        }

        // Respiratory rate above the age-appropriate threshold
        if (vitals.getRespiratoryRate() != null && vitals.getRespiratoryRate() > sirsRrThreshold) {
            respiratoryRateCriteriaMet = true;
            sirsScore++;
            findings.add("Respiratory rate elevated for SIRS: " + vitals.getRespiratoryRate() + " (>" + sirsRrThreshold
                    + (pediatric ? ", age-adjusted)" : ")"));
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

            // Adult hypotension threshold (SBP < 90).
            if (!pediatric && vitals.getSystolicBp() != null && vitals.getSystolicBp() < 90) {
                hasOrganDysfunction = true;
                systolicBpLow = true;
                findings.add("Organ dysfunction: SBP < 90 mmHg (hypotension)");
            }
            // Pediatric hypotension — assessed against the PALS 5th-percentile
            // systolic-BP-for-age threshold (NOT the adult cutoff). Frank
            // hypotension in a child is a decompensated (late) sign; previously
            // children could not reach SEVERE_SEPSIS on blood pressure at all.
            else if (pediatric && pedHypotensionSbp > 0
                    && vitals.getSystolicBp() != null && vitals.getSystolicBp() < pedHypotensionSbp) {
                hasOrganDysfunction = true;
                systolicBpLow = true;
                findings.add("Organ dysfunction: pediatric hypotension — SBP " + vitals.getSystolicBp()
                        + " mmHg < " + pedHypotensionSbp + " mmHg (PALS 5th-percentile for age)");
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
        if (!pediatric && status == SepsisStatus.SEVERE_SEPSIS
                && vitals.getSystolicBp() != null && vitals.getSystolicBp() < 70) {
            status = SepsisStatus.SEPTIC_SHOCK;
            findings.add("SEPTIC SHOCK: Persistent severe hypotension (SBP < 70 mmHg)");
        }
        // Pediatric: frank hypotension below the PALS 5th-percentile-for-age
        // threshold is, by definition, decompensated shock in a child. PALS does
        // not define a separate lower "shock" SBP and pediatric shock is often
        // normotensive/compensated, so we do NOT invent an unvalidated second
        // cutoff — an overtly hypotensive child is treated as septic shock.
        // Single-reading proxy; confirm fluid-refractory status clinically.
        else if (pediatric && status == SepsisStatus.SEVERE_SEPSIS && pedHypotensionSbp > 0
                && vitals.getSystolicBp() != null && vitals.getSystolicBp() < pedHypotensionSbp) {
            status = SepsisStatus.SEPTIC_SHOCK;
            findings.add("SEPTIC SHOCK: pediatric hypotension below PALS 5th-percentile for age (SBP "
                    + vitals.getSystolicBp() + " < " + pedHypotensionSbp + " mmHg)");
        }

        boolean bundleRequired = status == SepsisStatus.SEPSIS_SUSPECTED
                || status == SepsisStatus.SEVERE_SEPSIS
                || status == SepsisStatus.SEPTIC_SHOCK;

        if (bundleRequired) {
            findings.add("1-HOUR SEPSIS BUNDLE REQUIRED — initiate immediately");
        }

        // ================================================================
        // DATA QUALITY — a negative built on missing vitals must NOT reassure
        // ================================================================
        List<String> missing = new ArrayList<>();
        if (vitals.getTemperature() == null) missing.add("temperature");
        if (vitals.getHeartRate() == null) missing.add("heart rate");
        if (vitals.getRespiratoryRate() == null) missing.add("respiratory rate");
        if (vitals.getSystolicBp() == null) missing.add("systolic BP");
        if (vitals.getAvpu() == null && vitals.getGcsScore() == null) missing.add("mentation (AVPU/GCS)");
        int present = 5 - missing.size();
        boolean insufficientData = present < 3;
        String dataQualityNote = missing.isEmpty() ? null
                : "Screened with missing vitals: " + String.join(", ", missing)
                  + (insufficientData
                        ? " — INSUFFICIENT DATA: a negative result is NOT reassuring; re-screen when vitals are complete."
                        : ".");
        if (insufficientData) {
            findings.add("** INSUFFICIENT DATA ** — scored on only " + present
                    + "/5 core vitals (missing: " + String.join(", ", missing) + "). Do not treat a negative as reassuring.");
        }

        // ================================================================
        // PEDIATRIC CAVEAT — adult qSOFA is not validated in children
        // ================================================================
        String pediatricCaveat = null;
        if (pediatric) {
            pediatricCaveat = "Pediatric patient: SIRS vital thresholds were age-adjusted and blood pressure was "
                    + "assessed against PALS 5th-percentile-for-age hypotension thresholds. The adult qSOFA "
                    + "respiratory-rate threshold is NOT validated for children and was not applied; compensated "
                    + "pediatric septic shock can be normotensive, so a normal BP does NOT exclude shock — apply "
                    + "clinical judgment and age-specific assessment; do not treat a negative screen as reassuring.";
            findings.add("PEDIATRIC: age-adjusted SIRS thresholds + PALS hypotension-for-age applied; adult qSOFA RR not applied.");
        }

        log.info("Sepsis screening for Visit {}: Status={}, qSOFA={}, SIRS={}, Bundle={}, Pediatric={}, InsufficientData={}",
                visit.getVisitNumber(), status, qsofaScore, sirsScore, bundleRequired, pediatric, insufficientData);

        return new SepsisScreeningResult(
                status, qsofaScore, sirsScore,
                alteredMentation, respiratoryRateHigh, systolicBpLow,
                temperatureCriteriaMet, heartRateCriteriaMet, respiratoryRateCriteriaMet, wbcCriteriaMet,
                findings, bundleRequired,
                pediatric, pediatricCaveat, insufficientData, dataQualityNote
        );
    }

    /**
     * Boundary (months) between the infant and child SIRS bands — &lt; 36 months
     * (3 years) is treated as an infant. Also the conservative default age used
     * when a pediatric visit's date of birth cannot be resolved, mirroring the
     * triage pipeline's child-form default (TriageService.ageInMonths).
     */
    private static final int INFANT_AGE_BOUNDARY_MONTHS = 36;

    /**
     * Resolve the patient's age in whole months from date of birth. Null-safe:
     * returns the conservative child default ({@link #INFANT_AGE_BOUNDARY_MONTHS})
     * when the patient or DOB is unknown, and clamps negatives to 0. Months
     * precision (not years) is required because the PALS hypotension bands turn
     * on a &lt;1-month / &lt;1-year distinction a year-only age cannot express.
     */
    private static int resolveAgeMonths(Visit visit) {
        try {
            if (visit.getPatient() != null && visit.getPatient().getDateOfBirth() != null) {
                long months = java.time.temporal.ChronoUnit.MONTHS.between(
                        visit.getPatient().getDateOfBirth(), java.time.LocalDate.now());
                return (int) Math.max(0, months);
            }
        } catch (Exception ignored) {
            // fall through to the conservative default
        }
        return INFANT_AGE_BOUNDARY_MONTHS;
    }

    /**
     * PALS 5th-percentile systolic blood pressure (mmHg) below which a child is
     * hypotensive, by age:
     * <ul>
     *   <li>&lt; 1 month (term neonate): &lt; 60</li>
     *   <li>1 month – &lt; 1 year:       &lt; 70</li>
     *   <li>1 – 10 years:               &lt; 70 + (age_years × 2)</li>
     *   <li>&gt; 10 years:               &lt; 90</li>
     * </ul>
     * (Pediatric Advanced Life Support; Surviving Sepsis Campaign — pediatric.)
     */
    private static int pediatricHypotensionThreshold(int ageMonths) {
        if (ageMonths < 1) return 60;
        if (ageMonths < 12) return 70;
        if (ageMonths < 120) {
            int ageYears = ageMonths / 12;
            return 70 + (ageYears * 2);
        }
        return 90;
    }
}
