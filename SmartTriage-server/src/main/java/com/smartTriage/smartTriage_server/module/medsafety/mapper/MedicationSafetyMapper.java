package com.smartTriage.smartTriage_server.module.medsafety.mapper;

import com.smartTriage.smartTriage_server.module.medsafety.dto.DrugFormularyResponse;
import com.smartTriage.smartTriage_server.module.medsafety.dto.MedicationSafetyCheckResponse;
import com.smartTriage.smartTriage_server.module.medsafety.dto.MedicationSafetyResponse;
import com.smartTriage.smartTriage_server.module.medsafety.engine.MedicationSafetyResult;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import com.smartTriage.smartTriage_server.module.medsafety.entity.MedicationSafetyCheck;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;

/**
 * Maps medication safety entities and engine results to response DTOs.
 */
public final class MedicationSafetyMapper {

    private MedicationSafetyMapper() {}

    public static MedicationSafetyResponse toSafetyResponse(MedicationSafetyResult result) {
        String severity;
        if (!result.blockers().isEmpty()) {
            severity = "CRITICAL";
        } else if (!result.warnings().isEmpty()) {
            severity = "HIGH";
        } else {
            severity = "NORMAL";
        }

        return MedicationSafetyResponse.builder()
                .allergyCheckPassed(result.allergyCheckResult().passed())
                .allergyWarning(result.allergyCheckResult().message())
                .doseCheckPassed(result.doseCheckResult().passed())
                .doseWarning(result.doseCheckResult().message())
                .interactionCheckPassed(result.interactionCheckResult().passed())
                .interactionWarning(result.interactionCheckResult().message())
                .duplicateTherapyCheckPassed(result.duplicateCheckResult().passed())
                .duplicateWarning(result.duplicateCheckResult().message())
                .overallSafe(result.overallSafe())
                .warnings(result.warnings())
                .blockers(result.blockers())
                .severity(severity)
                // Workflow 2 — structured allergy metadata. Null fields when no
                // allergy match. The dialog uses these to render the right
                // flavour (soft/moderate/hard/anaphylaxis).
                .allergyMatchSeverity(result.allergyMatchSeverity() != null
                        ? result.allergyMatchSeverity().name() : null)
                .allergyMatchedAllergen(result.allergyMatchedAllergen())
                .allergyReaction(result.allergyReaction())
                .build();
    }

    public static MedicationSafetyCheckResponse toCheckResponse(MedicationSafetyCheck check) {
        Visit visit = check.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;
        return MedicationSafetyCheckResponse.builder()
                .id(check.getId())
                .visitId(visit != null ? visit.getId() : null)
                .medicationId(check.getMedication() != null ? check.getMedication().getId() : null)
                // Denormalised patient context (who + where) for the list row.
                .patientName(patient != null
                        ? (safe(patient.getFirstName()) + " " + safe(patient.getLastName())).trim()
                        : null)
                .visitNumber(visit != null ? visit.getVisitNumber() : null)
                .currentZone(visit != null ? visit.getCurrentEdZone() : null)
                .currentBedLabel(visit != null && visit.getCurrentBed() != null
                        ? visit.getCurrentBed().getCode()
                        : null)
                .checkedAt(check.getCheckedAt())
                .drugName(check.getDrugName())
                .prescribedDoseMg(check.getPrescribedDoseMg())
                .patientWeightKg(check.getPatientWeightKg())
                .allergyCheckPassed(check.isAllergyCheckPassed())
                .allergyWarning(check.getAllergyWarning())
                .doseCheckPassed(check.isDoseCheckPassed())
                .doseWarning(check.getDoseWarning())
                .interactionCheckPassed(check.isInteractionCheckPassed())
                .interactionWarning(check.getInteractionWarning())
                .duplicateTherapyCheckPassed(check.isDuplicateTherapyCheckPassed())
                .duplicateWarning(check.getDuplicateWarning())
                .overallSafe(check.isOverallSafe())
                .overriddenBy(check.getOverriddenBy())
                .overrideReason(check.getOverrideReason())
                .overriddenAt(check.getOverriddenAt())
                .createdAt(check.getCreatedAt())
                .updatedAt(check.getUpdatedAt())
                .build();
    }

    public static DrugFormularyResponse toFormularyResponse(DrugFormulary formulary) {
        return DrugFormularyResponse.builder()
                .id(formulary.getId())
                .genericName(formulary.getGenericName())
                .brandNames(formulary.getBrandNames())
                .drugClass(formulary.getDrugClass())
                .atcCode(formulary.getAtcCode())
                .remlCategory(formulary.getRemlCategory())
                .adultMinDoseMg(formulary.getAdultMinDoseMg())
                .adultMaxDoseMg(formulary.getAdultMaxDoseMg())
                .adultMaxDailyDoseMg(formulary.getAdultMaxDailyDoseMg())
                .pediatricMinDoseMgPerKg(formulary.getPediatricMinDoseMgPerKg())
                .pediatricMaxDoseMgPerKg(formulary.getPediatricMaxDoseMgPerKg())
                .pediatricMaxDailyDoseMgPerKg(formulary.getPediatricMaxDailyDoseMgPerKg())
                .geriatricAdjustmentPercent(formulary.getGeriatricAdjustmentPercent())
                .renalAdjustmentRequired(formulary.isRenalAdjustmentRequired())
                .hepaticAdjustmentRequired(formulary.isHepaticAdjustmentRequired())
                .availableRoutes(formulary.getAvailableRoutes())
                .contraindications(formulary.getContraindications())
                .majorInteractions(formulary.getMajorInteractions())
                .allergenGroups(formulary.getAllergenGroups())
                .isHighAlert(formulary.isHighAlert())
                .requiresDoubleCheck(formulary.isRequiresDoubleCheck())
                .blackBoxWarning(formulary.getBlackBoxWarning())
                .pregnancyCategory(formulary.getPregnancyCategory())
                .isOnReml(formulary.isOnReml())
                .hospitalId(formulary.getHospital() != null ? formulary.getHospital().getId() : null)
                .createdAt(formulary.getCreatedAt())
                .updatedAt(formulary.getUpdatedAt())
                .build();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
