package com.smartTriage.smartTriage_server.module.triage.engine;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Rwanda Adult Triage Decision Engine — implements the EXACT flowchart
 * from the standard Rwandan Adult Triage Form (Over 12 years).
 *
 * This is the national standard triage form used across all hospitals
 * in Rwanda, based on the modified South African Triage Scale (mSAT).
 *
 * Decision Flowchart (read top-to-bottom):
 *
 *   1. EMERGENCY SIGNS? → YES → RED (Immediate Resuscitation / ALARM)
 *   2. No → Calculate TEWS
 *   3. TEWS = 7-14 OR Sat < 92% → RED (Immediate Resuscitation)
 *   4. TEWS = 5-6 → Check Very Urgent Signs
 *      - Very Urgent Signs? YES → ORANGE (Less than 10 min)
 *      - No and TEWS = 5-6 → ORANGE (Less than 10 min)
 *      (Effectively: TEWS 5-6 is always ORANGE, but we still capture the discriminators)
 *   5. TEWS = 0-4 → Check Very Urgent Signs
 *      - Very Urgent Signs? YES → ORANGE (Less than 10 min)
 *      - No and TEWS = 3-4 → YELLOW (Less than 30 min)
 *      - No and TEWS = 0-2 → Check Urgent Signs
 *        - Urgent Signs? YES → YELLOW (Less than 30 min)
 *        - No → GREEN (Less than 1 hour, Reassess every 30 min)
 *
 * Special considerations (bottom of form):
 *   - Acute trauma
 *   - Seizure history
 *   - Any assault / abuse
 *   - Suicide attempt
 *
 * This engine takes the TEWS score, emergency signs assessment, discriminator
 * assessments, and SpO2 as input and returns the final triage category
 * following the exact Rwandan national triage protocol.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RwandaTriageDecisionEngine {

    /**
     * Result of the triage decision engine — includes the category and the
     * reasoning path for audit/medico-legal purposes.
     */
    public record TriageDecisionResult(
            TriageCategory category,
            String decisionPath,
            boolean emergencySignsTriggered,
            boolean saturationTriggered,
            boolean veryUrgentSignsTriggered,
            boolean urgentSignsTriggered
    ) {}

    /**
     * Execute the Rwanda adult triage decision flowchart.
     *
     * @param tewsScore       Computed TEWS score
     * @param spo2            SpO2 percentage (nullable)
     * @param painScore       Pain score 0-10 (nullable)
     * @param request         Full triage request with emergency/VU/U signs
     * @return                The triage decision result with category + audit trail
     */
    public TriageDecisionResult decide(
            int tewsScore,
            Integer spo2,
            Integer painScore,
            PerformTriageRequest request
    ) {
        // ====================================================================
        // STEP 1: EMERGENCY SIGNS? → RED
        // ====================================================================
        if (hasEmergencySigns(request)) {
            log.warn("Triage Decision: EMERGENCY SIGNS DETECTED → RED");
            return new TriageDecisionResult(
                    TriageCategory.RED,
                    "Emergency signs present → RED (Immediate Resuscitation / ALARM)",
                    true, false, false, false
            );
        }

        // ====================================================================
        // STEP 2: Calculate TEWS (already done) + Check SpO2 / Pain overrides
        // ====================================================================

        // TEWS 7-14 OR SpO2 < 92% → RED
        if (tewsScore >= 7) {
            log.warn("Triage Decision: TEWS={} (≥7) → RED", tewsScore);
            return new TriageDecisionResult(
                    TriageCategory.RED,
                    String.format("TEWS = %d (≥7) → RED (Immediate Resuscitation)", tewsScore),
                    false, false, false, false
            );
        }

        if (spo2 != null && spo2 < 92) {
            log.warn("Triage Decision: SpO2={}% (<92%) → RED", spo2);
            return new TriageDecisionResult(
                    TriageCategory.RED,
                    String.format("SpO2 = %d%% (<92%%) → RED (Immediate Resuscitation)", spo2),
                    false, true, false, false
            );
        }

        // ====================================================================
        // STEP 3: TEWS 5-6 → ORANGE (with discriminator capture)
        // ====================================================================
        if (tewsScore >= 5 && tewsScore <= 6) {
            boolean hasVU = hasVeryUrgentSigns(request);
            log.info("Triage Decision: TEWS={} (5-6) → ORANGE. Very Urgent Signs: {}", tewsScore, hasVU);
            return new TriageDecisionResult(
                    TriageCategory.ORANGE,
                    String.format("TEWS = %d (5-6) → ORANGE (Very Urgent, <10 min). VU Signs: %s",
                            tewsScore, hasVU),
                    false, false, hasVU, false
            );
        }

        // ====================================================================
        // STEP 4: TEWS 0-4 → Check Very Urgent Signs
        // ====================================================================
        boolean hasVeryUrgent = hasVeryUrgentSigns(request);

        if (hasVeryUrgent) {
            log.info("Triage Decision: TEWS={} + Very Urgent Signs → ORANGE", tewsScore);
            return new TriageDecisionResult(
                    TriageCategory.ORANGE,
                    String.format("TEWS = %d (0-4) + Very Urgent Signs present → ORANGE (<10 min)",
                            tewsScore),
                    false, false, true, false
            );
        }

        // No Very Urgent Signs, TEWS 3-4 → YELLOW
        if (tewsScore >= 3 && tewsScore <= 4) {
            log.info("Triage Decision: TEWS={} (3-4), no VU signs → YELLOW", tewsScore);
            return new TriageDecisionResult(
                    TriageCategory.YELLOW,
                    String.format("TEWS = %d (3-4), no Very Urgent Signs → YELLOW (<30 min)",
                            tewsScore),
                    false, false, false, false
            );
        }

        // ====================================================================
        // STEP 5: TEWS 0-2, no Very Urgent Signs → Check Urgent Signs
        // ====================================================================
        boolean hasUrgent = hasUrgentSigns(request);

        if (hasUrgent) {
            log.info("Triage Decision: TEWS={} + Urgent Signs → YELLOW", tewsScore);
            return new TriageDecisionResult(
                    TriageCategory.YELLOW,
                    String.format("TEWS = %d (0-2) + Urgent Signs present → YELLOW (<30 min)",
                            tewsScore),
                    false, false, false, true
            );
        }

        // ====================================================================
        // STEP 6: No signs at all → GREEN
        // ====================================================================
        log.info("Triage Decision: TEWS={}, no discriminator signs → GREEN", tewsScore);
        return new TriageDecisionResult(
                TriageCategory.GREEN,
                String.format("TEWS = %d (0-2), no Very Urgent/Urgent Signs → GREEN (<1 hour, reassess every 30 min)",
                        tewsScore),
                false, false, false, false
        );
    }

    // ====================================================================
    // Emergency Signs — Section 1 of Rwanda standard adult triage form
    // ====================================================================

    /**
     * Check if ANY emergency sign is present.
     * "Emergency Signs? CHECK THE COMPLAINT"
     *
     * - Airway/Breathing: Not breathing, Obstructed breathing, Severe respiratory distress
     * - Circulation: Cardiac arrest, Haemorrhage – uncontrolled, Stab/gunshot wound to neck/chest
     * - Convulsions: Current seizure or post-ictal
     * - Coma: Unresponsive or responsive only to pain
     * - Other: Hypoglycaemia (Glucose < 3 mmol/L or 60 mg/dL), Purpuric rash, Burn – face/inhalation
     */
    private boolean hasEmergencySigns(PerformTriageRequest r) {
        return r.isHasAirwayCompromise()
                || r.isHasBreathingDistress()
                || r.isHasSevereRespiratoryDistress()
                || r.isHasCardiacArrest()
                || r.isHasUncontrolledHaemorrhage()
                || r.isHasStabGunWoundNeckChest()
                || r.isHasConvulsions()
                || r.isHasComa()
                || r.isHasHypoglycaemia()
                || r.isHasPurpuricRash()
                || r.isHasBurnFaceInhalation();
    }

    // ====================================================================
    // Very Urgent Signs — Section 2 of Rwanda standard adult triage form
    // ====================================================================

    /**
     * Check if ANY Very Urgent sign is present.
     * "Very Urgent Signs? CHECK THE COMPLAINT"
     *
     * Medical:
     *  - Focal neurologic deficit – acute (<1 day)
     *  - Altered mental status – acute (<1 day)
     *  - Chest pain
     *  - Poisoning / Overdose
     *  - Pregnant + abdominal pain
     *  - Coughing or vomiting blood
     *  - Unwell with diabetes, glucose > 200 mg/dL or 11 mmol/L
     *  - Aggression
     *  - Shortness of breath – acute (<1 day)
     *
     * Trauma:
     *  - Burn over 20%, or urgent signs (electrical, chemical, circumferential)
     *  - Fracture – Open (with skin break)
     *  - Threatened limb (no pulses or pale)
     *  - Eye injury
     *  - Dislocation of larger joint (not finger/toe)
     *  - Severe mechanism of injury (Fall > 1 meter, RTA, significant trauma)
     *  - Very severe pain (≥ 7)
     *  - Pregnant + abdominal trauma
     */
    private boolean hasVeryUrgentSigns(PerformTriageRequest r) {
        // Medical Very Urgent
        return r.isVuFocalNeurologicDeficit()
                || r.isVuAlteredMentalStatus()
                || r.isVuChestPain()
                || r.isVuPoisoningOverdose()
                || r.isVuPregnantAbdominalPain()
                || r.isVuCoughingVomitingBlood()
                || r.isVuDiabeticHighGlucose()
                || r.isVuAggression()
                || r.isVuShortnessOfBreath()
                // Trauma Very Urgent
                || r.isVuBurnOver20Percent()
                || r.isVuOpenFracture()
                || r.isVuThreatenedLimb()
                || r.isVuEyeInjury()
                || r.isVuLargeJointDislocation()
                || r.isVuSevereMechanismOfInjury()
                || r.isVuVerySeverePain()
                || r.isVuPregnantAbdominalTrauma();
    }

    // ====================================================================
    // Urgent Signs — Section 3 of Rwanda standard adult triage form
    // ====================================================================

    /**
     * Check if ANY Urgent sign is present.
     * "Urgent signs? CHECK THE COMPLAINT"
     *
     *  - Unable to drink or vomits everything
     *  - Abdominal pain
     *  - Very pale
     *  - Pregnant + vaginal bleeding
     *  - Diabetic, glucose > 300 mg/dL or 17 mmol/L
     *  - Dislocation – finger or toe
     *  - Fracture – closed
     *  - Burn without urgent signs
     *  - Pregnant + trauma (not abdominal)
     *  - Moderate pain (5-6)
     *  - Laceration, abscess
     *  - Foreign body aspiration
     */
    private boolean hasUrgentSigns(PerformTriageRequest r) {
        return r.isUrgUnableToDrinkVomits()
                || r.isUrgAbdominalPain()
                || r.isUrgVeryPale()
                || r.isUrgPregnantVaginalBleeding()
                || r.isUrgDiabeticVeryHighGlucose()
                || r.isUrgFingerToeDislocation()
                || r.isUrgClosedFracture()
                || r.isUrgBurnWithoutUrgentSigns()
                || r.isUrgPregnantTraumaNonAbdominal()
                || r.isUrgModeratePain()
                || r.isUrgLacerationAbscess()
                || r.isUrgForeignBodyAspiration();
    }
}
