package com.smartTriage.smartTriage_server.module.medsafety.engine;

import com.smartTriage.smartTriage_server.common.enums.AllergySeverity;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import com.smartTriage.smartTriage_server.module.medsafety.repository.DrugFormularyRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PatientAllergy;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientAllergyRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MedicationSafetyEngine — validates prescriptions against allergy records,
 * enforces safe dose ranges using the Rwanda Essential Medicines List (REML),
 * and handles pediatric weight-based dosage calculations.
 *
 * This engine runs ALL checks and returns a composite MedicationSafetyResult.
 * It does NOT block execution — the calling service decides whether to block
 * based on the severity of findings.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicationSafetyEngine {

    private final DrugFormularyRepository formularyRepository;
    private final MedicationAdministrationRepository medicationRepository;
    private final PatientAllergyRepository patientAllergyRepository;

    /**
     * Cross-allergenicity map: if a patient is allergic to the key, they may
     * also react to all values in the associated set.
     */
    private static final Map<String, Set<String>> CROSS_ALLERGENICITY = Map.of(
            "penicillin", Set.of("beta-lactam", "amoxicillin", "ampicillin", "piperacillin", "cephalosporin"),
            "beta-lactam", Set.of("penicillin", "cephalosporin", "carbapenem"),
            "cephalosporin", Set.of("beta-lactam", "penicillin"),
            "sulfa", Set.of("sulfonamide", "sulfamethoxazole", "sulfasalazine"),
            "nsaid", Set.of("aspirin", "ibuprofen", "diclofenac", "naproxen")
    );

    /**
     * Run all medication safety checks for a prescription.
     *
     * @param med      the medication administration record being validated
     * @param patient  the patient receiving the medication
     * @param visit    the current visit
     * @param weightKg patient weight in kilograms (required for pediatric dosing)
     * @return composite result with all check outcomes
     */
    public MedicationSafetyResult validatePrescription(
            MedicationAdministration med,
            Patient patient,
            Visit visit,
            Double weightKg) {

        log.info("Running medication safety checks for drug '{}' on patient {} (visit {})",
                med.getDrugName(), patient.getId(), visit.getId());

        // Look up formulary entry for this drug
        Optional<DrugFormulary> formularyOpt = findFormularyEntry(med.getDrugName(), visit);

        // Parse prescribed dose from the dose string
        Double prescribedDoseMg = parseDoseMg(med.getDose());

        // Run individual checks
        AllergyCheckOutcome allergyOutcome = checkAllergies(patient, formularyOpt.orElse(null), med.getDrugName());
        MedicationSafetyResult.CheckResult allergyResult = allergyOutcome.checkResult();
        MedicationSafetyResult.CheckResult doseResult = checkDoseRange(formularyOpt.orElse(null), prescribedDoseMg, patient, weightKg);
        MedicationSafetyResult.CheckResult interactionResult = checkDrugInteractions(formularyOpt.orElse(null), visit, med);
        MedicationSafetyResult.CheckResult duplicateResult = checkDuplicateTherapy(formularyOpt.orElse(null), visit, med);

        // Aggregate warnings and blockers
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        aggregateResult(allergyResult, warnings, blockers);
        aggregateResult(doseResult, warnings, blockers);
        aggregateResult(interactionResult, warnings, blockers);
        aggregateResult(duplicateResult, warnings, blockers);

        boolean overallSafe = blockers.isEmpty() && warnings.isEmpty();

        log.info("Safety check complete for drug '{}': overallSafe={}, warnings={}, blockers={}, allergySeverity={}",
                med.getDrugName(), overallSafe, warnings.size(), blockers.size(),
                allergyOutcome.matchedSeverity());

        return new MedicationSafetyResult(
                allergyResult,
                doseResult,
                interactionResult,
                duplicateResult,
                overallSafe,
                warnings,
                blockers,
                allergyOutcome.matchedSeverity(),
                allergyOutcome.matchedAllergenName(),
                allergyOutcome.matchedReaction()
        );
    }

    /**
     * Internal wrapper that pairs the engine's {@link
     * MedicationSafetyResult.CheckResult} for the allergy check with
     * the structured severity / allergen / reaction that triggered
     * the match (when one was found). The metadata flows through
     * {@link MedicationSafetyResult} to the prescribe-time safety
     * dialog so it can render the right flavour (soft warning for
     * MILD, hard stop for SEVERE/ANAPHYLAXIS).
     */
    private record AllergyCheckOutcome(
            MedicationSafetyResult.CheckResult checkResult,
            AllergySeverity matchedSeverity,
            String matchedAllergenName,
            String matchedReaction
    ) {
        static AllergyCheckOutcome ok() {
            return new AllergyCheckOutcome(
                    MedicationSafetyResult.CheckResult.ok(), null, null, null);
        }
    }

    // ====================================================================
    // PUBLIC — focused allergy assessment for prescribe-time ENFORCEMENT (S1)
    // ====================================================================

    /**
     * Public, side-effect-free allergy assessment used by
     * {@code MedicationService.prescribe} to ENFORCE an allergy block
     * server-side (S1 — defense-in-depth).
     *
     * <p>Reuses the EXACT same detection logic as the full
     * {@link #validatePrescription} run — structured {@link PatientAllergy}
     * rows, legacy free-text fallback, formulary allergen-group match, and
     * the curated {@link #CROSS_ALLERGENICITY} expansion — but returns only
     * the allergy outcome. It runs no dose / interaction / duplicate checks
     * and persists nothing, so it is cheap and safe to call inline on the
     * prescribe path.
     *
     * @return an {@link AllergyAssessment}; {@link AllergyAssessment#isBlocking()}
     *         is true when a non-MILD allergy matched and the prescription
     *         must therefore not proceed without an explicit override.
     */
    public AllergyAssessment assessAllergyForPrescription(Patient patient, Visit visit, String drugName) {
        if (patient == null || visit == null || drugName == null || drugName.isBlank()) {
            return AllergyAssessment.none();
        }
        Optional<DrugFormulary> formularyOpt = findFormularyEntry(drugName, visit);
        AllergyCheckOutcome outcome = checkAllergies(patient, formularyOpt.orElse(null), drugName);
        boolean matched = !outcome.checkResult().passed();
        return new AllergyAssessment(
                matched,
                outcome.matchedSeverity(),
                outcome.matchedAllergenName(),
                outcome.matchedReaction(),
                outcome.checkResult().message());
    }

    /**
     * Public result of {@link #assessAllergyForPrescription}.
     *
     * <p>{@link #isBlocking()} is true when a non-MILD allergy matched.
     * MILD matches set {@code matched=true} but are NOT blocking — they
     * remain a soft warning, preserving the existing graded-severity
     * behaviour (anti-alert-fatigue).
     */
    public record AllergyAssessment(
            boolean matched,
            AllergySeverity severity,
            String allergenName,
            String reaction,
            String message
    ) {
        public static AllergyAssessment none() {
            return new AllergyAssessment(false, null, null, null, null);
        }

        public boolean isBlocking() {
            return matched && severity != null && severity.isBlocking();
        }
    }

    // ====================================================================
    // ALLERGY CHECK
    // ====================================================================

    /**
     * Allergy check — Workflow 2.
     *
     * <p>Consults the structured {@link PatientAllergy} rows first. If
     * none exist for the patient (un-migrated record), falls back to
     * parsing the legacy free-text {@code Patient.knownAllergies}
     * column. Multiple structured matches are reconciled by picking
     * the highest {@link AllergySeverity#rank()} so the dialog
     * flavour reflects the worst recorded reaction.
     *
     * <p>Severity mapping into {@link MedicationSafetyResult.Severity}:
     * <ul>
     *   <li>{@code ANAPHYLAXIS} / {@code SEVERE} → CRITICAL (hard
     *       stop — goes into {@code blockers}).</li>
     *   <li>{@code MODERATE} / {@code UNKNOWN} → CRITICAL (hard stop
     *       — we don't downgrade unknown reactions).</li>
     *   <li>{@code MILD} → HIGH (soft warning — goes into {@code
     *       warnings}; the dialog renders an acknowledge-only
     *       flavour rather than a hard block).</li>
     * </ul>
     */
    private AllergyCheckOutcome checkAllergies(
            Patient patient,
            DrugFormulary formulary,
            String drugName) {

        // 1) Structured allergies — the authoritative source post-V58.
        List<PatientAllergy> structuredAllergies =
                patientAllergyRepository.findActiveByPatientId(patient.getId());

        if (!structuredAllergies.isEmpty()) {
            return checkStructuredAllergies(structuredAllergies, formulary, drugName);
        }

        // 2) Fallback: legacy free-text knownAllergies for patients whose
        //    profile hasn't been re-captured yet. Severity is unknowable
        //    from this column — surface as a structured MODERATE (hard
        //    stop with override-reason required) rather than CRITICAL so
        //    we don't permanently anchor at the worst case for the
        //    legacy population.
        return checkLegacyFreeTextAllergies(patient, formulary, drugName);
    }

    private AllergyCheckOutcome checkStructuredAllergies(
            List<PatientAllergy> patientAllergies,
            DrugFormulary formulary,
            String drugName) {

        String drugNameLower = drugName.toLowerCase().trim();
        Set<String> drugAllergenGroups = formulary != null && formulary.getAllergenGroups() != null
                ? parseCommaList(formulary.getAllergenGroups())
                : Collections.emptySet();

        PatientAllergy worstMatch = null;
        String worstMatchReason = null;  // human-readable match reason

        for (PatientAllergy allergy : patientAllergies) {
            String allergenLower = allergy.getAllergenName() != null
                    ? allergy.getAllergenName().toLowerCase().trim()
                    : "";
            if (allergenLower.isEmpty()) continue;

            String matchReason = null;

            // a) Formulary FK match — the patient's allergy row points to
            //    the same formulary entry as the prescribed drug. The
            //    strongest possible signal.
            if (allergy.getAllergenFormulary() != null && formulary != null
                    && allergy.getAllergenFormulary().getId() != null
                    && allergy.getAllergenFormulary().getId().equals(formulary.getId())) {
                matchReason = String.format(
                        "Patient is allergic to '%s' — prescribed drug '%s' is the same formulary entry",
                        allergy.getAllergenName(), drugName);
            }
            // b) Direct name substring match in either direction.
            else if (drugNameLower.contains(allergenLower) || allergenLower.contains(drugNameLower)) {
                matchReason = String.format(
                        "Patient is allergic to '%s' — prescribed drug '%s' is a direct name match",
                        allergy.getAllergenName(), drugName);
            }
            // c) Drug's allergen group contains the patient's allergen.
            else if (!drugAllergenGroups.isEmpty() && drugAllergenGroups.contains(allergenLower)) {
                matchReason = String.format(
                        "Patient allergy '%s' matches drug allergen group for '%s'",
                        allergy.getAllergenName(), drugName);
            }
            // d) Cross-allergenicity expansion.
            else if (!drugAllergenGroups.isEmpty()) {
                Set<String> crossReactive = CROSS_ALLERGENICITY.getOrDefault(
                        allergenLower, Collections.emptySet());
                for (String crossAllergen : crossReactive) {
                    if (drugAllergenGroups.contains(crossAllergen)) {
                        matchReason = String.format(
                                "Cross-allergy: Patient allergy '%s' is cross-reactive with '%s' (allergen group of '%s')",
                                allergy.getAllergenName(), crossAllergen, drugName);
                        break;
                    }
                }
            }

            if (matchReason == null) continue;

            AllergySeverity candidateSeverity = allergy.getSeverity() != null
                    ? allergy.getSeverity() : AllergySeverity.UNKNOWN;

            if (worstMatch == null
                    || candidateSeverity.rank() > (worstMatch.getSeverity() != null
                            ? worstMatch.getSeverity().rank() : 0)) {
                worstMatch = allergy;
                worstMatchReason = matchReason;
            }
        }

        if (worstMatch == null) {
            return AllergyCheckOutcome.ok();
        }

        AllergySeverity severity = worstMatch.getSeverity() != null
                ? worstMatch.getSeverity() : AllergySeverity.UNKNOWN;
        String message = formatAllergyMessage(severity, worstMatchReason,
                worstMatch.getReaction());
        log.warn(message);

        MedicationSafetyResult.CheckResult checkResult = severity == AllergySeverity.MILD
                ? MedicationSafetyResult.CheckResult.warning(message)
                : MedicationSafetyResult.CheckResult.critical(message);

        return new AllergyCheckOutcome(
                checkResult,
                severity,
                worstMatch.getAllergenName(),
                worstMatch.getReaction());
    }

    private AllergyCheckOutcome checkLegacyFreeTextAllergies(
            Patient patient,
            DrugFormulary formulary,
            String drugName) {

        if (patient.getKnownAllergies() == null || patient.getKnownAllergies().isBlank()) {
            return AllergyCheckOutcome.ok();
        }

        Set<String> patientAllergies = parseCommaList(patient.getKnownAllergies());
        String drugNameLower = drugName.toLowerCase().trim();

        String matchedAllergen = null;
        String matchReason = null;

        // Direct drug name match.
        for (String allergy : patientAllergies) {
            if (drugNameLower.contains(allergy) || allergy.contains(drugNameLower)) {
                matchedAllergen = allergy;
                matchReason = String.format(
                        "Patient is allergic to '%s' — prescribed drug '%s' is a direct name match",
                        allergy, drugName);
                break;
            }
        }

        // Allergen-group + cross-reactivity match.
        if (matchReason == null && formulary != null
                && formulary.getAllergenGroups() != null
                && !formulary.getAllergenGroups().isBlank()) {
            Set<String> drugAllergenGroups = parseCommaList(formulary.getAllergenGroups());
            outer:
            for (String allergy : patientAllergies) {
                if (drugAllergenGroups.contains(allergy)) {
                    matchedAllergen = allergy;
                    matchReason = String.format(
                            "Patient allergy '%s' matches drug allergen group for '%s'",
                            allergy, drugName);
                    break;
                }
                Set<String> crossReactive = CROSS_ALLERGENICITY.getOrDefault(
                        allergy, Collections.emptySet());
                for (String crossAllergen : crossReactive) {
                    if (drugAllergenGroups.contains(crossAllergen)) {
                        matchedAllergen = allergy;
                        matchReason = String.format(
                                "Cross-allergy: Patient allergy '%s' is cross-reactive with '%s' (allergen group of '%s')",
                                allergy, crossAllergen, drugName);
                        break outer;
                    }
                }
            }
        }

        if (matchReason == null) {
            return AllergyCheckOutcome.ok();
        }

        // Legacy free-text doesn't carry severity. Treat as MODERATE —
        // safe middle ground: hard stop with override-reason required,
        // but the prescriber isn't anchored at ANAPHYLAXIS for an
        // allergy whose reaction was never recorded.
        AllergySeverity assumedSeverity = AllergySeverity.MODERATE;
        String message = formatAllergyMessage(assumedSeverity, matchReason, null);
        log.warn(message);

        return new AllergyCheckOutcome(
                MedicationSafetyResult.CheckResult.critical(message),
                assumedSeverity,
                matchedAllergen,
                null);
    }

    private String formatAllergyMessage(
            AllergySeverity severity, String matchReason, String reaction) {
        StringBuilder sb = new StringBuilder("ALLERGY ALERT (");
        sb.append(severity.name()).append("): ").append(matchReason);
        if (reaction != null && !reaction.isBlank()) {
            sb.append(". Prior reaction: ").append(reaction);
        }
        return sb.toString();
    }

    // ====================================================================
    // DOSE RANGE CHECK
    // ====================================================================

    private MedicationSafetyResult.CheckResult checkDoseRange(
            DrugFormulary formulary,
            Double prescribedDoseMg,
            Patient patient,
            Double weightKg) {

        if (formulary == null || prescribedDoseMg == null) {
            return MedicationSafetyResult.CheckResult.ok();
        }

        // S2 — unit-aware guard. The formulary's numeric bounds are
        // interpreted in formulary.doseUnit (see DrugFormulary.doseUnit).
        // prescribedDoseMg is parsed as milligrams. A numeric comparison
        // is only valid when the formulary bounds are themselves in mg.
        // For UNITS/IU/G/MCG/ML/SACHETS/… the stored bounds are not
        // mg-comparable (and for magnesium sulfate the V31 unit/value pair
        // is internally inconsistent), so comparing would yield false
        // over/under-dose findings. Skip the numeric range check for those;
        // allergy / interaction / duplicate checks already ran upstream.
        if (!isMgDosed(formulary)) {
            log.debug("Dose-range check skipped for '{}' — non-mg unit '{}' is not mg-comparable",
                    formulary.getGenericName(), formulary.getDoseUnit());
            return MedicationSafetyResult.CheckResult.ok();
        }

        boolean isPediatric = patient.isPediatric();

        if (isPediatric) {
            return checkPediatricDose(formulary, prescribedDoseMg, weightKg);
        } else {
            return checkAdultDose(formulary, prescribedDoseMg);
        }
    }

    /**
     * True when the formulary's numeric dose bounds are expressed in
     * milligrams and are therefore directly comparable to the parsed-mg
     * prescribed dose. Null / blank dose_unit is treated as MG (the column
     * default) so legacy rows keep their existing behaviour.
     */
    private boolean isMgDosed(DrugFormulary formulary) {
        String unit = formulary.getDoseUnit();
        return unit == null || unit.isBlank() || "MG".equalsIgnoreCase(unit.trim());
    }

    private MedicationSafetyResult.CheckResult checkPediatricDose(
            DrugFormulary formulary,
            Double prescribedDoseMg,
            Double weightKg) {

        if (weightKg == null || weightKg <= 0) {
            return MedicationSafetyResult.CheckResult.warning(
                    "DOSE CHECK: Patient weight not provided — cannot validate pediatric dose for " + formulary.getGenericName());
        }

        Double minMgPerKg = formulary.getPediatricMinDoseMgPerKg();
        Double maxMgPerKg = formulary.getPediatricMaxDoseMgPerKg();

        if (minMgPerKg == null && maxMgPerKg == null) {
            return MedicationSafetyResult.CheckResult.ok();
        }

        double minDose = minMgPerKg != null ? minMgPerKg * weightKg : 0;
        double maxDose = maxMgPerKg != null ? maxMgPerKg * weightKg : Double.MAX_VALUE;

        MedicationSafetyResult.DoseStatus status = evaluateDoseStatus(prescribedDoseMg, minDose, maxDose);

        return switch (status) {
            case NORMAL -> MedicationSafetyResult.CheckResult.ok();
            case UNDERDOSE -> MedicationSafetyResult.CheckResult.warning(
                    String.format("UNDERDOSE: Prescribed %.1f mg is below pediatric minimum of %.1f mg (%.2f mg/kg x %.1f kg) for %s",
                            prescribedDoseMg, minDose, minMgPerKg != null ? minMgPerKg : 0, weightKg, formulary.getGenericName()));
            case OVERDOSE -> MedicationSafetyResult.CheckResult.warning(
                    String.format("OVERDOSE WARNING: Prescribed %.1f mg exceeds pediatric maximum of %.1f mg (%.2f mg/kg x %.1f kg) for %s",
                            prescribedDoseMg, maxDose, maxMgPerKg != null ? maxMgPerKg : 0, weightKg, formulary.getGenericName()));
            case CRITICAL_OVERDOSE -> MedicationSafetyResult.CheckResult.critical(
                    String.format("CRITICAL OVERDOSE: Prescribed %.1f mg is >200%% of pediatric maximum %.1f mg (%.2f mg/kg x %.1f kg) for %s",
                            prescribedDoseMg, maxDose, maxMgPerKg != null ? maxMgPerKg : 0, weightKg, formulary.getGenericName()));
        };
    }

    private MedicationSafetyResult.CheckResult checkAdultDose(
            DrugFormulary formulary,
            Double prescribedDoseMg) {

        Double minDose = formulary.getAdultMinDoseMg();
        Double maxDose = formulary.getAdultMaxDoseMg();

        if (minDose == null && maxDose == null) {
            return MedicationSafetyResult.CheckResult.ok();
        }

        double effectiveMin = minDose != null ? minDose : 0;
        double effectiveMax = maxDose != null ? maxDose : Double.MAX_VALUE;

        MedicationSafetyResult.DoseStatus status = evaluateDoseStatus(prescribedDoseMg, effectiveMin, effectiveMax);

        return switch (status) {
            case NORMAL -> MedicationSafetyResult.CheckResult.ok();
            case UNDERDOSE -> MedicationSafetyResult.CheckResult.warning(
                    String.format("UNDERDOSE: Prescribed %.1f mg is below adult minimum of %.1f mg for %s",
                            prescribedDoseMg, effectiveMin, formulary.getGenericName()));
            case OVERDOSE -> MedicationSafetyResult.CheckResult.warning(
                    String.format("OVERDOSE WARNING: Prescribed %.1f mg exceeds adult maximum of %.1f mg for %s",
                            prescribedDoseMg, effectiveMax, formulary.getGenericName()));
            case CRITICAL_OVERDOSE -> MedicationSafetyResult.CheckResult.critical(
                    String.format("CRITICAL OVERDOSE: Prescribed %.1f mg is >200%% of adult maximum %.1f mg for %s",
                            prescribedDoseMg, effectiveMax, formulary.getGenericName()));
        };
    }

    private MedicationSafetyResult.DoseStatus evaluateDoseStatus(double prescribed, double min, double max) {
        if (prescribed < min) {
            return MedicationSafetyResult.DoseStatus.UNDERDOSE;
        } else if (prescribed <= max) {
            return MedicationSafetyResult.DoseStatus.NORMAL;
        } else if (prescribed <= max * 2.0) {
            return MedicationSafetyResult.DoseStatus.OVERDOSE;
        } else {
            return MedicationSafetyResult.DoseStatus.CRITICAL_OVERDOSE;
        }
    }

    // ====================================================================
    // DRUG INTERACTION CHECK
    // ====================================================================

    private MedicationSafetyResult.CheckResult checkDrugInteractions(
            DrugFormulary formulary,
            Visit visit,
            MedicationAdministration currentMed) {

        if (formulary == null || formulary.getMajorInteractions() == null || formulary.getMajorInteractions().isBlank()) {
            return MedicationSafetyResult.CheckResult.ok();
        }

        Set<String> interactionDrugs = parseCommaList(formulary.getMajorInteractions());

        // Get all active medications for this visit
        List<MedicationAdministration> activeMeds = medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visit.getId());

        List<String> interactionPairs = new ArrayList<>();

        for (MedicationAdministration activeMed : activeMeds) {
            // Skip the medication being validated
            if (activeMed.getId() != null && activeMed.getId().equals(currentMed.getId())) {
                continue;
            }

            // Only check PRESCRIBED or ADMINISTERED medications
            if (activeMed.getStatus() != MedicationStatus.PRESCRIBED
                    && activeMed.getStatus() != MedicationStatus.ADMINISTERED) {
                continue;
            }

            String activeDrugNameLower = activeMed.getDrugName().toLowerCase().trim();
            for (String interactionDrug : interactionDrugs) {
                if (activeDrugNameLower.contains(interactionDrug) || interactionDrug.contains(activeDrugNameLower)) {
                    interactionPairs.add(String.format("%s <-> %s", currentMed.getDrugName(), activeMed.getDrugName()));
                }
            }
        }

        if (interactionPairs.isEmpty()) {
            return MedicationSafetyResult.CheckResult.ok();
        }

        String msg = String.format("DRUG INTERACTION: Major interactions detected — %s",
                String.join("; ", interactionPairs));
        log.warn(msg);
        return MedicationSafetyResult.CheckResult.warning(msg);
    }

    // ====================================================================
    // DUPLICATE THERAPY CHECK
    // ====================================================================

    private MedicationSafetyResult.CheckResult checkDuplicateTherapy(
            DrugFormulary formulary,
            Visit visit,
            MedicationAdministration currentMed) {

        if (formulary == null || formulary.getDrugClass() == null || formulary.getDrugClass().isBlank()) {
            return MedicationSafetyResult.CheckResult.ok();
        }

        String currentDrugClass = formulary.getDrugClass().toLowerCase().trim();

        // Get all active medications for this visit
        List<MedicationAdministration> activeMeds = medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visit.getId());

        for (MedicationAdministration activeMed : activeMeds) {
            // Skip the medication being validated
            if (activeMed.getId() != null && activeMed.getId().equals(currentMed.getId())) {
                continue;
            }

            // Only check PRESCRIBED or ADMINISTERED medications
            if (activeMed.getStatus() != MedicationStatus.PRESCRIBED
                    && activeMed.getStatus() != MedicationStatus.ADMINISTERED) {
                continue;
            }

            // Look up the active medication in the formulary
            Optional<DrugFormulary> activeDrugFormulary = findFormularyEntry(activeMed.getDrugName(), visit);
            if (activeDrugFormulary.isPresent()
                    && activeDrugFormulary.get().getDrugClass() != null
                    && activeDrugFormulary.get().getDrugClass().toLowerCase().trim().equals(currentDrugClass)) {
                String msg = String.format(
                        "DUPLICATE THERAPY: '%s' and '%s' are both in drug class '%s' — potential therapeutic duplication",
                        currentMed.getDrugName(), activeMed.getDrugName(), formulary.getDrugClass());
                log.warn(msg);
                return MedicationSafetyResult.CheckResult.warning(msg);
            }
        }

        return MedicationSafetyResult.CheckResult.ok();
    }

    // ====================================================================
    // HELPERS
    // ====================================================================

    /**
     * Find formulary entry by drug name, checking hospital-specific first, then system-wide.
     */
    private Optional<DrugFormulary> findFormularyEntry(String drugName, Visit visit) {
        UUID hospitalId = visit.getHospital().getId();

        // Try hospital-specific first
        Optional<DrugFormulary> result = formularyRepository
                .findByGenericNameIgnoreCaseAndHospitalIdAndIsActiveTrue(drugName.trim(), hospitalId);
        if (result.isPresent()) {
            return result;
        }

        // Fall back to system-wide
        return formularyRepository
                .findByGenericNameIgnoreCaseAndHospitalIsNullAndIsActiveTrue(drugName.trim());
    }

    /**
     * Parse a comma-separated string into a set of lowercase trimmed values.
     */
    private Set<String> parseCommaList(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Parse dose in mg from a free-text dose string (e.g., "500mg", "1g", "250 mg").
     */
    private Double parseDoseMg(String doseStr) {
        if (doseStr == null || doseStr.isBlank()) {
            return null;
        }

        String cleaned = doseStr.trim().toLowerCase().replaceAll("\\s+", "");

        try {
            if (cleaned.endsWith("mg")) {
                return Double.parseDouble(cleaned.replace("mg", ""));
            } else if (cleaned.endsWith("g")) {
                return Double.parseDouble(cleaned.replace("g", "")) * 1000;
            } else if (cleaned.endsWith("mcg") || cleaned.endsWith("ug")) {
                String numPart = cleaned.replaceAll("(mcg|ug)$", "");
                return Double.parseDouble(numPart) / 1000;
            } else {
                // Try parsing as plain number (assume mg)
                return Double.parseDouble(cleaned.replaceAll("[^0-9.]", ""));
            }
        } catch (NumberFormatException e) {
            log.debug("Could not parse dose from string: '{}'", doseStr);
            return null;
        }
    }

    private void aggregateResult(
            MedicationSafetyResult.CheckResult result,
            List<String> warnings,
            List<String> blockers) {

        if (result.passed()) {
            return;
        }
        if (result.severity() == MedicationSafetyResult.Severity.CRITICAL) {
            blockers.add(result.message());
        } else {
            warnings.add(result.message());
        }
    }
}
