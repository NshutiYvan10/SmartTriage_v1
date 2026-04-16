package com.smartTriage.smartTriage_server.module.triage.engine;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Rwanda Child Triage Decision Engine — implements the EXACT flowchart
 * from the standard Rwandan Child Triage Form (3-12 years).
 *
 * This is the national standard child triage form used across all hospitals
 * in Rwanda, based on the modified South African Triage Scale (mSAT).
 *
 * The decision flowchart is IDENTICAL to the adult form:
 *
 *   1. EMERGENCY SIGNS? → YES → RED (Immediate Resuscitation / ALARM)
 *   2. No → Calculate TEWS (using CHILD thresholds)
 *   3. TEWS = 7-14 OR Sat < 92% → RED (Immediate Resuscitation)
 *   4. TEWS = 5-6 → ORANGE (capture VU discriminators)
 *   5. TEWS = 0-4 + Very Urgent Signs? → ORANGE
 *   6. TEWS = 3-4, no VU → YELLOW
 *   7. TEWS = 0-2 + Urgent Signs? → YELLOW
 *   8. No signs → GREEN
 *
 * KEY DIFFERENCES from adult form:
 *   - Emergency Signs Section 1 has CHILD-SPECIFIC signs:
 *     - Central cyanosis (not in adult)
 *     - Pulse low or absent (not in adult)
 *     - Cold hands + (lethargic OR pulse weak/fast OR cap refill ≥ 3s) (not in adult)
 *     - Severe dehydration ≥ +2 of: skin pinch ≥ 2s, lethargy, sunken eyes (not in adult)
 *   - TEWS uses child-specific vital sign thresholds (PediatricTewsCalculator)
 *   - Very Urgent and Urgent Signs (back of form) are the SAME as adult
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RwandaPediatricTriageDecisionEngine {

    /**
     * Result of the pediatric triage decision engine — includes the category
     * and the reasoning path for audit/medico-legal purposes.
     * Uses the same structure as the adult engine for consistency.
     */
    public record PediatricTriageDecisionResult(
            TriageCategory category,
            String decisionPath,
            boolean emergencySignsTriggered,
            boolean saturationTriggered,
            boolean veryUrgentSignsTriggered,
            boolean urgentSignsTriggered
    ) {}

    /**
     * Execute the Rwanda child triage decision flowchart.
     *
     * @param tewsScore       Computed TEWS score (from PediatricTewsCalculator)
     * @param spo2            SpO2 percentage (nullable)
     * @param painScore       Pain score 0-10 (nullable)
     * @param request         Full triage request with emergency/VU/U signs
     * @return                The triage decision result with category + audit trail
     */
    public PediatricTriageDecisionResult decide(
            int tewsScore,
            Integer spo2,
            Integer painScore,
            PerformTriageRequest request
    ) {
        // ====================================================================
        // STEP 1: EMERGENCY SIGNS? → RED
        // Child form Section 1 — different from adult
        // ====================================================================
        if (hasChildEmergencySigns(request)) {
            log.warn("Pediatric Triage Decision: EMERGENCY SIGNS DETECTED → RED");
            return new PediatricTriageDecisionResult(
                    TriageCategory.RED,
                    "Child Emergency signs present → RED (Immediate Resuscitation / ALARM)",
                    true, false, false, false
            );
        }

        // ====================================================================
        // STEP 2: TEWS (already calculated with child thresholds) + SpO2
        // ====================================================================

        // TEWS 7-14 OR SpO2 < 92% → RED
        if (tewsScore >= 7) {
            log.warn("Pediatric Triage Decision: TEWS={} (≥7) → RED", tewsScore);
            return new PediatricTriageDecisionResult(
                    TriageCategory.RED,
                    String.format("Child TEWS = %d (≥7) → RED (Immediate Resuscitation)", tewsScore),
                    false, false, false, false
            );
        }

        if (spo2 != null && spo2 < 92) {
            log.warn("Pediatric Triage Decision: SpO2={}% (<92%) → RED", spo2);
            return new PediatricTriageDecisionResult(
                    TriageCategory.RED,
                    String.format("Child SpO2 = %d%% (<92%%) → RED (Immediate Resuscitation)", spo2),
                    false, true, false, false
            );
        }

        // ====================================================================
        // STEP 3: TEWS 5-6 → ORANGE (with discriminator capture)
        // ====================================================================
        if (tewsScore >= 5 && tewsScore <= 6) {
            boolean hasVU = hasVeryUrgentSigns(request);
            log.info("Pediatric Triage Decision: TEWS={} (5-6) → ORANGE. Very Urgent Signs: {}", tewsScore, hasVU);
            return new PediatricTriageDecisionResult(
                    TriageCategory.ORANGE,
                    String.format("Child TEWS = %d (5-6) → ORANGE (Very Urgent, <10 min). VU Signs: %s",
                            tewsScore, hasVU),
                    false, false, hasVU, false
            );
        }

        // ====================================================================
        // STEP 4: TEWS 0-4 → Check Very Urgent Signs
        // (Back of form — same as adult)
        // ====================================================================
        boolean hasVeryUrgent = hasVeryUrgentSigns(request);

        if (hasVeryUrgent) {
            log.info("Pediatric Triage Decision: TEWS={} + Very Urgent Signs → ORANGE", tewsScore);
            return new PediatricTriageDecisionResult(
                    TriageCategory.ORANGE,
                    String.format("Child TEWS = %d (0-4) + Very Urgent Signs present → ORANGE (<10 min)",
                            tewsScore),
                    false, false, true, false
            );
        }

        // No Very Urgent Signs, TEWS 3-4 → YELLOW
        if (tewsScore >= 3 && tewsScore <= 4) {
            log.info("Pediatric Triage Decision: TEWS={} (3-4), no VU signs → YELLOW", tewsScore);
            return new PediatricTriageDecisionResult(
                    TriageCategory.YELLOW,
                    String.format("Child TEWS = %d (3-4), no Very Urgent Signs → YELLOW (<30 min)",
                            tewsScore),
                    false, false, false, false
            );
        }

        // ====================================================================
        // STEP 5: TEWS 0-2, no Very Urgent Signs → Check Urgent Signs
        // (Back of form — same as adult)
        // ====================================================================
        boolean hasUrgent = hasUrgentSigns(request);

        if (hasUrgent) {
            log.info("Pediatric Triage Decision: TEWS={} + Urgent Signs → YELLOW", tewsScore);
            return new PediatricTriageDecisionResult(
                    TriageCategory.YELLOW,
                    String.format("Child TEWS = %d (0-2) + Urgent Signs present → YELLOW (<30 min)",
                            tewsScore),
                    false, false, false, true
            );
        }

        // ====================================================================
        // STEP 6: No signs at all → GREEN
        // ====================================================================
        log.info("Pediatric Triage Decision: TEWS={}, no discriminator signs → GREEN", tewsScore);
        return new PediatricTriageDecisionResult(
                TriageCategory.GREEN,
                String.format("Child TEWS = %d (0-2), no Very Urgent/Urgent Signs → GREEN (<1 hour, reassess every 30 min)",
                        tewsScore),
                false, false, false, false
        );
    }

    // ====================================================================
    // CHILD Emergency Signs — Section 1 of Rwanda standard CHILD triage form
    //
    // These are DIFFERENT from the adult form emergency signs.
    // ====================================================================

    /**
     * Check if ANY child emergency sign is present.
     * "Emergency Signs? CHECK THE COMPLAINT"
     *
     * Airway / Breathing:
     *   □ Not breathing or weak breathing
     *   □ Obstructed breathing
     *   □ Central cyanosis               ← CHILD-SPECIFIC (not in adult)
     *   □ Severe respiratory distress
     *
     * Circulation:
     *   □ Cardiac arrest
     *   □ Haemorrhage – uncontrolled
     *   □ Pulse low or absent            ← CHILD-SPECIFIC (not in adult)
     *   □ Cold hands plus: □ lethargic OR □ pulse weak and fast
     *     OR □ cap refill ≥ 3 sec        ← CHILD-SPECIFIC composite sign
     *
     * Convulsions:
     *   □ Current seizure or post ictal (not alert) (Glucose = ___)
     *
     * Coma:
     *   □ Unresponsive or responsive only to pain (Glucose = ___)
     *
     * Dehydration:                        ← CHILD-SPECIFIC section (not in adult)
     *   □ Severe dehydration ≥ +2 of the following:
     *     □ Skin pinch ≥ 2 sec  □ Lethargy  □ Sunken eyes
     *
     * Other:
     *   □ Hypoglycaemia (Glucose < 3 mmol/L or 60 mg/dL)
     *   □ Purpuric rash
     *   □ Burn – face/inhalation
     */
    private boolean hasChildEmergencySigns(PerformTriageRequest r) {
        // Airway / Breathing (shared + child-specific)
        return r.isHasAirwayCompromise()          // Not breathing or weak breathing / Obstructed
                || r.isHasBreathingDistress()      // (generic breathing distress)
                || r.isChildCentralCyanosis()      // ← CHILD-SPECIFIC
                || r.isHasSevereRespiratoryDistress()

                // Circulation (shared + child-specific)
                || r.isHasCardiacArrest()
                || r.isHasUncontrolledHaemorrhage()
                || r.isChildPulseLowOrAbsent()     // ← CHILD-SPECIFIC
                || r.isChildColdHandsComposite()   // ← CHILD-SPECIFIC composite

                // Convulsions & Coma (same as adult)
                || r.isHasConvulsions()
                || r.isHasComa()

                // Dehydration (CHILD-SPECIFIC — entire section)
                || r.isChildSevereDehydration()    // ← CHILD-SPECIFIC

                // Other (same as adult)
                || r.isHasHypoglycaemia()
                || r.isHasPurpuricRash()
                || r.isHasBurnFaceInhalation();
    }

    // ====================================================================
    // Very Urgent Signs — back of form (SAME as adult form)
    // ====================================================================

    /**
     * Check if ANY Very Urgent sign is present.
     * The back of the child form is identical to the adult form.
     * Same Medical + Trauma discriminators.
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
    // Urgent Signs — back of form (SAME as adult form)
    // ====================================================================

    /**
     * Check if ANY Urgent sign is present.
     * The back of the child form is identical to the adult form.
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
