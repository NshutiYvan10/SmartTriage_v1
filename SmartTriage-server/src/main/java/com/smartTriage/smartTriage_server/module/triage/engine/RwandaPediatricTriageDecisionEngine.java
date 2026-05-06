package com.smartTriage.smartTriage_server.module.triage.engine;

import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KFH Pediatric Triage Decision Engine — implements the EXACT flowcharts
 * from the official KFH Infant Triage Form (0–3 years) and Child Triage
 * Form (3–12 years).
 *
 * <p>The two forms share the same flowchart shape but differ in:
 * <ul>
 *   <li>TEWS vital-sign thresholds (handled in
 *       {@link PediatricTewsCalculator})</li>
 *   <li>Very Urgent / Urgent discriminator lists (handled here)</li>
 * </ul>
 *
 * <p>The decision flowchart for both forms:
 *
 * <pre>
 *   1. EMERGENCY SIGNS? → YES → RED (Immediate Resuscitation / ALARM)
 *   2. No → Calculate TEWS (using age-appropriate thresholds)
 *   3. TEWS ≥ 7 OR SpO2 &lt; 92% → RED
 *   4. TEWS 5-6 → ORANGE
 *   5. TEWS 0-4 + Very Urgent Signs? → ORANGE
 *   6. TEWS 3-4, no VU → YELLOW (TEWS-only YELLOW path per form)
 *   7. TEWS 0-2 + Urgent Signs? → YELLOW
 *   8. No signs, TEWS 0-2 → GREEN
 * </pre>
 *
 * <p>Critical correction over the prior implementation: the back-of-form
 * VU and URG checks are NOT identical to the adult form. The KFH peds
 * forms have items like "Floppy/irritable", "Tiny baby &lt;2 months"
 * (infant only), "Severe malnutrition/wasting", "Pitting oedema" that
 * are not on the adult form. The adult form has items like "Coughing
 * /vomiting blood", "Aggression", "Shortness of breath" that are NOT on
 * the peds forms. Using adult-form VU/URG for peds was a clinical
 * accuracy failure this engine now corrects.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RwandaPediatricTriageDecisionEngine {

    public record PediatricTriageDecisionResult(
            TriageCategory category,
            String decisionPath,
            boolean emergencySignsTriggered,
            boolean saturationTriggered,
            boolean veryUrgentSignsTriggered,
            boolean urgentSignsTriggered
    ) {}

    /**
     * Execute the KFH peds triage decision. Caller has already
     * computed TEWS using {@link PediatricTewsCalculator} with the
     * patient's age in months.
     *
     * @param ageInMonths   patient's age in months (drives infant-vs-child
     *                      branching for items unique to one form)
     * @param tewsScore     pre-computed TEWS score
     * @param spo2          SpO2 percentage (nullable)
     * @param painScore     pain score 0-10 (nullable)
     * @param request       full triage request with all checked discriminators
     */
    public PediatricTriageDecisionResult decide(
            int ageInMonths,
            int tewsScore,
            Integer spo2,
            Integer painScore,
            PerformTriageRequest request
    ) {
        boolean isInfant = ageInMonths < PediatricTewsCalculator.INFANT_AGE_BOUNDARY_MONTHS;
        String formLabel = isInfant ? "Infant 0-3" : "Child 3-12";

        // ────────────────────────────────────────────────────────
        // STEP 1 — Emergency Signs (front of form) → RED
        // ────────────────────────────────────────────────────────
        if (hasPedsEmergencySigns(request)) {
            log.warn("Pediatric Triage [{}]: EMERGENCY SIGNS → RED", formLabel);
            return new PediatricTriageDecisionResult(
                    TriageCategory.RED,
                    formLabel + " Emergency signs present → RED (Immediate Resuscitation / ALARM)",
                    true, false, false, false
            );
        }

        // ────────────────────────────────────────────────────────
        // STEP 2 — TEWS + SpO2 → RED
        // ────────────────────────────────────────────────────────
        if (tewsScore >= 7) {
            log.warn("Pediatric Triage [{}]: TEWS={} (≥7) → RED", formLabel, tewsScore);
            return new PediatricTriageDecisionResult(
                    TriageCategory.RED,
                    formLabel + " TEWS = " + tewsScore + " (≥7) → RED (Immediate Resuscitation)",
                    false, false, false, false
            );
        }
        if (spo2 != null && spo2 < 92) {
            log.warn("Pediatric Triage [{}]: SpO2={}% (<92%) → RED", formLabel, spo2);
            return new PediatricTriageDecisionResult(
                    TriageCategory.RED,
                    formLabel + " SpO2 = " + spo2 + "% (<92%) → RED (Immediate Resuscitation)",
                    false, true, false, false
            );
        }

        // ────────────────────────────────────────────────────────
        // STEP 3 — TEWS 5-6 → ORANGE
        // ────────────────────────────────────────────────────────
        if (tewsScore == 5 || tewsScore == 6) {
            boolean hasVU = hasPedsVeryUrgentSigns(request, isInfant, painScore);
            log.info("Pediatric Triage [{}]: TEWS={} (5-6) → ORANGE. VU: {}",
                    formLabel, tewsScore, hasVU);
            return new PediatricTriageDecisionResult(
                    TriageCategory.ORANGE,
                    formLabel + " TEWS = " + tewsScore + " (5-6) → ORANGE (Very Urgent, <10 min). VU: " + hasVU,
                    false, false, hasVU, false
            );
        }

        // ────────────────────────────────────────────────────────
        // STEP 4 — TEWS 0-4 + VU? → ORANGE
        // ────────────────────────────────────────────────────────
        boolean hasVU = hasPedsVeryUrgentSigns(request, isInfant, painScore);
        if (hasVU) {
            log.info("Pediatric Triage [{}]: TEWS={} + VU signs → ORANGE", formLabel, tewsScore);
            return new PediatricTriageDecisionResult(
                    TriageCategory.ORANGE,
                    formLabel + " TEWS = " + tewsScore + " (0-4) + Very Urgent signs → ORANGE (<10 min)",
                    false, false, true, false
            );
        }

        // TEWS 3-4 without VU → YELLOW (per form's "No and TEWS=3-4" arrow)
        if (tewsScore == 3 || tewsScore == 4) {
            log.info("Pediatric Triage [{}]: TEWS={} (3-4), no VU → YELLOW", formLabel, tewsScore);
            return new PediatricTriageDecisionResult(
                    TriageCategory.YELLOW,
                    formLabel + " TEWS = " + tewsScore + " (3-4), no VU → YELLOW (<30 min)",
                    false, false, false, false
            );
        }

        // ────────────────────────────────────────────────────────
        // STEP 5 — TEWS 0-2 + URG? → YELLOW
        // ────────────────────────────────────────────────────────
        boolean hasURG = hasPedsUrgentSigns(request);
        if (hasURG) {
            log.info("Pediatric Triage [{}]: TEWS={} + URG signs → YELLOW", formLabel, tewsScore);
            return new PediatricTriageDecisionResult(
                    TriageCategory.YELLOW,
                    formLabel + " TEWS = " + tewsScore + " (0-2) + Urgent signs → YELLOW (<30 min)",
                    false, false, false, true
            );
        }

        // ────────────────────────────────────────────────────────
        // STEP 6 — Default → GREEN
        // ────────────────────────────────────────────────────────
        log.info("Pediatric Triage [{}]: TEWS={}, no discriminators → GREEN", formLabel, tewsScore);
        return new PediatricTriageDecisionResult(
                TriageCategory.GREEN,
                formLabel + " TEWS = " + tewsScore + ", no Very Urgent/Urgent signs → GREEN (<1 hour, reassess every 30 min)",
                false, false, false, false
        );
    }

    // ════════════════════════════════════════════════════════════
    // Emergency Signs — KFH peds forms (Infant + Child are identical)
    // ════════════════════════════════════════════════════════════

    /**
     * Returns true when ANY peds Emergency Sign on the KFH form is
     * positive. Includes composite-sign logic for "Severe dehydration
     * ≥+2 of (skin pinch, lethargy, sunken eyes)" and "Cold hands +
     * (lethargic OR pulse-weak-fast OR cap-refill≥3s)" — the engine
     * fires the composite when sub-flags imply it, even if the nurse
     * forgot to tick the composite checkbox.
     */
    private boolean hasPedsEmergencySigns(PerformTriageRequest r) {
        // Airway / Breathing
        boolean airwayBreathing =
                r.isHasAirwayCompromise()
                        || r.isHasBreathingDistress()
                        || r.isHasSevereRespiratoryDistress()
                        || r.isChildCentralCyanosis();

        // Circulation — including the cold-hands composite
        boolean coldHandsComposite =
                r.isChildColdHandsComposite()
                        || r.isChildColdHandsLethargic()
                        || r.isChildColdHandsPulseWeakFast()
                        || r.isChildColdHandsCapRefill();
        // The form requires Cold-hands PLUS at least one associated
        // sign. We treat any sub-flag as implying the composite —
        // ticking only "lethargic" without ticking "cold hands"
        // doesn't make sense in form context, but if a nurse does
        // tick a sub-flag we should treat the composite as positive.
        // The prior implementation read only the composite and
        // missed cases where sub-flags were ticked alone.

        boolean circulation =
                r.isHasCardiacArrest()
                        || r.isHasUncontrolledHaemorrhage()
                        || r.isChildPulseLowOrAbsent()
                        || coldHandsComposite;

        // Severe dehydration composite — ≥+2 of skin pinch, lethargy,
        // sunken eyes (per form) OR the explicit composite flag.
        int dehydrationCount = 0;
        if (r.isChildDehydrationSkinPinch()) dehydrationCount++;
        if (r.isChildDehydrationLethargy()) dehydrationCount++;
        if (r.isChildDehydrationSunkenEyes()) dehydrationCount++;
        boolean dehydration = r.isChildSevereDehydration() || dehydrationCount >= 2;

        // Other — same items on adult and peds forms
        boolean other =
                r.isHasConvulsions()
                        || r.isHasComa()
                        || r.isHasHypoglycaemia()
                        || r.isHasPurpuricRash()
                        || r.isHasBurnFaceInhalation();

        return airwayBreathing || circulation || dehydration || other;
    }

    // ════════════════════════════════════════════════════════════
    // Very Urgent Signs — KFH peds form (back of form)
    //
    // Items here are the peds-specific VU list. We DO NOT check
    // adult-only items (vuCoughingVomitingBlood, vuAggression,
    // vuShortnessOfBreath, vuDiabeticHighGlucose) — those are not
    // on the KFH peds form.
    // ════════════════════════════════════════════════════════════

    private boolean hasPedsVeryUrgentSigns(
            PerformTriageRequest r, boolean isInfant, Integer painScore) {

        // Inconsolable crying / severe pain — infant >7, child ≥7
        boolean inconsolablePain = r.isVuPedsInconsolableSeverePain();
        if (painScore != null) {
            if (isInfant && painScore > 7) inconsolablePain = true;
            if (!isInfant && painScore >= 7) inconsolablePain = true;
        }

        // Tiny baby (<2 months) — infant form only. Defensive: ignore
        // the flag for non-infant visits even if it was ticked.
        boolean tinyBaby = isInfant && r.isVuPedsTinyBabyUnder2Months();

        // Medical
        boolean medical =
                r.isVuPedsMoreSleepyThanNormal()
                        || r.isVuFocalNeurologicDeficit()
                        || inconsolablePain
                        || r.isVuPedsFloppyIrritableRestless()
                        || r.isVuChestPain()
                        || r.isVuPoisoningOverdose()
                        || tinyBaby
                        // Pregnant + abdominal pain — peds child form only.
                        // Defensive: ignore for infant visits.
                        || (!isInfant && r.isVuPregnantAbdominalPain());

        // Trauma — peds form burn threshold is 10% (vs adult 20%)
        boolean trauma =
                r.isVuPedsBurnOver10Percent()
                        || r.isVuOpenFracture()
                        || r.isVuThreatenedLimb()
                        || r.isVuEyeInjury()
                        || r.isVuLargeJointDislocation()
                        || r.isVuSevereMechanismOfInjury()
                        // Pregnant + abdominal trauma — child form only
                        || (!isInfant && r.isVuPregnantAbdominalTrauma());

        return medical || trauma;
    }

    // ════════════════════════════════════════════════════════════
    // Urgent Signs — KFH peds form (back of form)
    // ════════════════════════════════════════════════════════════

    private boolean hasPedsUrgentSigns(PerformTriageRequest r) {
        // Composite "Diarrhoea/vomiting + dehydration sign" — any of
        // four sub-flags implies the composite if the nurse missed
        // ticking it.
        int dehydrationSubFlags = 0;
        if (r.isUrgPedsDehydrationSunkenEyes()) dehydrationSubFlags++;
        if (r.isUrgPedsDehydrationDryMouth()) dehydrationSubFlags++;
        if (r.isUrgPedsDehydrationDecreasedUrine()) dehydrationSubFlags++;
        if (r.isUrgPedsDehydrationSlowSkinPinch()) dehydrationSubFlags++;
        boolean diarrheaDehydration =
                r.isUrgPedsDiarrheaVomitingDehydration() || dehydrationSubFlags >= 1;

        // Medical
        boolean medical =
                r.isUrgPedsPittingEdemaFaceOrFeet()
                        || r.isUrgUnableToDrinkVomits()
                        || r.isUrgVeryPale()
                        || r.isUrgPedsSomeRespiratoryDistress()
                        // Pregnant + vaginal bleeding — child form only.
                        // The is-infant check is implicit: an infant won't
                        // have this flag set in normal use; if it is, we
                        // treat it as positive (defensive — pregnancy in a
                        // pediatric patient is itself an urgent finding).
                        || r.isUrgPregnantVaginalBleeding()
                        || diarrheaDehydration
                        || r.isUrgPedsSevereMalnutritionWasting()
                        || r.isUrgPedsUnwellWithKnownDiabetes();

        // Trauma — same items as adult URG
        boolean trauma =
                r.isUrgFingerToeDislocation()
                        || r.isUrgClosedFracture()
                        || r.isUrgBurnWithoutUrgentSigns()
                        || r.isUrgPregnantTraumaNonAbdominal()
                        || r.isUrgModeratePain()
                        || r.isUrgLacerationAbscess()
                        || r.isUrgForeignBodyAspiration();

        return medical || trauma;
    }
}
