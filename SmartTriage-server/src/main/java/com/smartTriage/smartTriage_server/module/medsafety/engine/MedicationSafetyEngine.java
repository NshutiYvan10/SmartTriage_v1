package com.smartTriage.smartTriage_server.module.medsafety.engine;

import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import com.smartTriage.smartTriage_server.module.medsafety.repository.DrugFormularyRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
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
        MedicationSafetyResult.CheckResult allergyResult = checkAllergies(patient, formularyOpt.orElse(null), med.getDrugName());
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

        log.info("Safety check complete for drug '{}': overallSafe={}, warnings={}, blockers={}",
                med.getDrugName(), overallSafe, warnings.size(), blockers.size());

        return new MedicationSafetyResult(
                allergyResult,
                doseResult,
                interactionResult,
                duplicateResult,
                overallSafe,
                warnings,
                blockers
        );
    }

    // ====================================================================
    // ALLERGY CHECK
    // ====================================================================

    private MedicationSafetyResult.CheckResult checkAllergies(
            Patient patient,
            DrugFormulary formulary,
            String drugName) {

        if (patient.getKnownAllergies() == null || patient.getKnownAllergies().isBlank()) {
            return MedicationSafetyResult.CheckResult.ok();
        }

        Set<String> patientAllergies = parseCommaList(patient.getKnownAllergies());

        // Direct drug name match
        String drugNameLower = drugName.toLowerCase().trim();
        for (String allergy : patientAllergies) {
            if (drugNameLower.contains(allergy) || allergy.contains(drugNameLower)) {
                String msg = String.format("ALLERGY ALERT: Patient is allergic to '%s' — prescribed drug '%s' is a direct match",
                        allergy, drugName);
                log.warn(msg);
                return MedicationSafetyResult.CheckResult.critical(msg);
            }
        }

        // Check against formulary allergen groups
        if (formulary != null && formulary.getAllergenGroups() != null && !formulary.getAllergenGroups().isBlank()) {
            Set<String> drugAllergenGroups = parseCommaList(formulary.getAllergenGroups());

            for (String allergy : patientAllergies) {
                // Direct allergen group match
                if (drugAllergenGroups.contains(allergy)) {
                    String msg = String.format("ALLERGY ALERT: Patient allergy '%s' matches drug allergen group for '%s'",
                            allergy, drugName);
                    log.warn(msg);
                    return MedicationSafetyResult.CheckResult.critical(msg);
                }

                // Cross-allergenicity check
                Set<String> crossReactive = CROSS_ALLERGENICITY.getOrDefault(allergy, Collections.emptySet());
                for (String crossAllergen : crossReactive) {
                    if (drugAllergenGroups.contains(crossAllergen)) {
                        String msg = String.format(
                                "CROSS-ALLERGY ALERT: Patient allergy '%s' has cross-reactivity with '%s' (allergen group of '%s')",
                                allergy, crossAllergen, drugName);
                        log.warn(msg);
                        return MedicationSafetyResult.CheckResult.critical(msg);
                    }
                }
            }
        }

        return MedicationSafetyResult.CheckResult.ok();
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

        boolean isPediatric = patient.isPediatric();

        if (isPediatric) {
            return checkPediatricDose(formulary, prescribedDoseMg, weightKg);
        } else {
            return checkAdultDose(formulary, prescribedDoseMg);
        }
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
