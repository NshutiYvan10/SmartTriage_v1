package com.smartTriage.smartTriage_server.module.medsafety.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.service.MedicationService;
import com.smartTriage.smartTriage_server.module.medsafety.dto.*;
import com.smartTriage.smartTriage_server.module.medsafety.engine.MedicationSafetyEngine;
import com.smartTriage.smartTriage_server.module.medsafety.engine.MedicationSafetyResult;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import com.smartTriage.smartTriage_server.module.medsafety.entity.MedicationSafetyCheck;
import com.smartTriage.smartTriage_server.module.medsafety.mapper.MedicationSafetyMapper;
import com.smartTriage.smartTriage_server.module.medsafety.repository.DrugFormularyRepository;
import com.smartTriage.smartTriage_server.module.medsafety.repository.MedicationSafetyCheckRepository;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MedicationSafetyService — orchestrates medication safety validation,
 * persists check results, manages formulary entries, and generates clinical
 * alerts for safety issues.
 *
 * On CRITICAL safety issues (allergy match, >200% overdose):
 *   Creates CRITICAL alert that BLOCKS administration until overridden.
 *
 * On HIGH safety issues (interaction, moderate overdose):
 *   Creates HIGH alert as a warning.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MedicationSafetyService {

    private final MedicationSafetyEngine safetyEngine;
    private final MedicationSafetyCheckRepository safetyCheckRepository;
    private final DrugFormularyRepository formularyRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final VisitService visitService;
    private final MedicationService medicationService;
    private final HospitalService hospitalService;

    // ====================================================================
    // VALIDATE PRESCRIPTION
    // ====================================================================

    /**
     * Run all medication safety checks for a prescription.
     * Persists the check result and generates alerts for safety issues.
     */
    @Transactional
    public MedicationSafetyCheckResponse validatePrescription(ValidatePrescriptionRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());
        MedicationAdministration medication = medicationService.findMedicationOrThrow(request.getMedicationId());
        Patient patient = visit.getPatient();

        // Run safety engine
        MedicationSafetyResult result = safetyEngine.validatePrescription(
                medication, patient, visit, request.getWeightKg());

        // Persist safety check
        MedicationSafetyCheck check = MedicationSafetyCheck.builder()
                .visit(visit)
                .medication(medication)
                .checkedAt(Instant.now())
                .drugName(medication.getDrugName())
                .prescribedDoseMg(request.getDoseMg())
                .patientWeightKg(request.getWeightKg())
                .allergyCheckPassed(result.allergyCheckResult().passed())
                .allergyWarning(result.allergyCheckResult().message())
                .doseCheckPassed(result.doseCheckResult().passed())
                .doseWarning(result.doseCheckResult().message())
                .interactionCheckPassed(result.interactionCheckResult().passed())
                .interactionWarning(result.interactionCheckResult().message())
                .duplicateTherapyCheckPassed(result.duplicateCheckResult().passed())
                .duplicateWarning(result.duplicateCheckResult().message())
                .overallSafe(result.overallSafe())
                .build();

        check = safetyCheckRepository.save(check);

        // Generate alerts for safety issues
        generateAlertsForSafetyResult(result, visit, medication);

        log.info("Medication safety check completed for medication {} on visit {}: overallSafe={}",
                medication.getId(), visit.getId(), result.overallSafe());

        return MedicationSafetyMapper.toCheckResponse(check);
    }

    // ====================================================================
    // OVERRIDE SAFETY CHECK
    // ====================================================================

    /**
     * Allow clinician to override a safety check with documented reason.
     */
    @Transactional
    public MedicationSafetyCheckResponse overrideSafetyCheck(UUID checkId, String reason, String overriddenBy) {
        MedicationSafetyCheck check = safetyCheckRepository.findByIdAndIsActiveTrue(checkId)
                .orElseThrow(() -> new ResourceNotFoundException("MedicationSafetyCheck", "id", checkId));

        if (check.isOverallSafe()) {
            throw new ClinicalBusinessException("Cannot override a safety check that already passed");
        }

        if (reason == null || reason.isBlank()) {
            throw new ClinicalBusinessException("Override reason is required for safety check override");
        }

        check.setOverriddenBy(overriddenBy);
        check.setOverrideReason(reason);
        check.setOverriddenAt(Instant.now());

        check = safetyCheckRepository.save(check);

        log.info("Medication safety check {} overridden by {} — reason: {}", checkId, overriddenBy, reason);

        return MedicationSafetyMapper.toCheckResponse(check);
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public Page<MedicationSafetyCheckResponse> getChecksForVisit(UUID visitId, Pageable pageable) {
        return safetyCheckRepository
                .findByVisitIdAndIsActiveTrueOrderByCheckedAtDesc(visitId, pageable)
                .map(MedicationSafetyMapper::toCheckResponse);
    }

    public Page<DrugFormularyResponse> getFormulary(UUID hospitalId, Pageable pageable) {
        return formularyRepository
                .findFormularyForHospital(hospitalId, pageable)
                .map(MedicationSafetyMapper::toFormularyResponse);
    }

    public List<DrugFormularyResponse> searchFormulary(String query) {
        return formularyRepository.searchByName(query)
                .stream()
                .map(MedicationSafetyMapper::toFormularyResponse)
                .collect(Collectors.toList());
    }

    // ====================================================================
    // FORMULARY MANAGEMENT
    // ====================================================================

    @Transactional
    public DrugFormularyResponse addFormularyEntry(DrugFormularyRequest request) {
        Hospital hospital = null;
        if (request.getHospitalId() != null) {
            hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());
        }

        DrugFormulary formulary = DrugFormulary.builder()
                .genericName(request.getGenericName())
                .brandNames(request.getBrandNames())
                .drugClass(request.getDrugClass())
                .atcCode(request.getAtcCode())
                .remlCategory(request.getRemlCategory())
                .adultMinDoseMg(request.getAdultMinDoseMg())
                .adultMaxDoseMg(request.getAdultMaxDoseMg())
                .adultMaxDailyDoseMg(request.getAdultMaxDailyDoseMg())
                .pediatricMinDoseMgPerKg(request.getPediatricMinDoseMgPerKg())
                .pediatricMaxDoseMgPerKg(request.getPediatricMaxDoseMgPerKg())
                .pediatricMaxDailyDoseMgPerKg(request.getPediatricMaxDailyDoseMgPerKg())
                .geriatricAdjustmentPercent(request.getGeriatricAdjustmentPercent())
                .renalAdjustmentRequired(request.getRenalAdjustmentRequired() != null && request.getRenalAdjustmentRequired())
                .hepaticAdjustmentRequired(request.getHepaticAdjustmentRequired() != null && request.getHepaticAdjustmentRequired())
                .availableRoutes(request.getAvailableRoutes())
                .contraindications(request.getContraindications())
                .majorInteractions(request.getMajorInteractions())
                .allergenGroups(request.getAllergenGroups())
                .isHighAlert(request.getIsHighAlert() != null && request.getIsHighAlert())
                .requiresDoubleCheck(request.getRequiresDoubleCheck() != null && request.getRequiresDoubleCheck())
                .blackBoxWarning(request.getBlackBoxWarning())
                .pregnancyCategory(request.getPregnancyCategory())
                .isOnReml(request.getIsOnReml() != null && request.getIsOnReml())
                .hospital(hospital)
                .build();

        formulary = formularyRepository.save(formulary);

        log.info("Formulary entry added: {} (hospital: {})",
                formulary.getGenericName(),
                hospital != null ? hospital.getName() : "system-wide");

        return MedicationSafetyMapper.toFormularyResponse(formulary);
    }

    @Transactional
    public DrugFormularyResponse updateFormularyEntry(UUID id, DrugFormularyRequest request) {
        DrugFormulary formulary = formularyRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("DrugFormulary", "id", id));

        if (request.getGenericName() != null) formulary.setGenericName(request.getGenericName());
        if (request.getBrandNames() != null) formulary.setBrandNames(request.getBrandNames());
        if (request.getDrugClass() != null) formulary.setDrugClass(request.getDrugClass());
        if (request.getAtcCode() != null) formulary.setAtcCode(request.getAtcCode());
        if (request.getRemlCategory() != null) formulary.setRemlCategory(request.getRemlCategory());
        if (request.getAdultMinDoseMg() != null) formulary.setAdultMinDoseMg(request.getAdultMinDoseMg());
        if (request.getAdultMaxDoseMg() != null) formulary.setAdultMaxDoseMg(request.getAdultMaxDoseMg());
        if (request.getAdultMaxDailyDoseMg() != null) formulary.setAdultMaxDailyDoseMg(request.getAdultMaxDailyDoseMg());
        if (request.getPediatricMinDoseMgPerKg() != null) formulary.setPediatricMinDoseMgPerKg(request.getPediatricMinDoseMgPerKg());
        if (request.getPediatricMaxDoseMgPerKg() != null) formulary.setPediatricMaxDoseMgPerKg(request.getPediatricMaxDoseMgPerKg());
        if (request.getPediatricMaxDailyDoseMgPerKg() != null) formulary.setPediatricMaxDailyDoseMgPerKg(request.getPediatricMaxDailyDoseMgPerKg());
        if (request.getGeriatricAdjustmentPercent() != null) formulary.setGeriatricAdjustmentPercent(request.getGeriatricAdjustmentPercent());
        if (request.getRenalAdjustmentRequired() != null) formulary.setRenalAdjustmentRequired(request.getRenalAdjustmentRequired());
        if (request.getHepaticAdjustmentRequired() != null) formulary.setHepaticAdjustmentRequired(request.getHepaticAdjustmentRequired());
        if (request.getAvailableRoutes() != null) formulary.setAvailableRoutes(request.getAvailableRoutes());
        if (request.getContraindications() != null) formulary.setContraindications(request.getContraindications());
        if (request.getMajorInteractions() != null) formulary.setMajorInteractions(request.getMajorInteractions());
        if (request.getAllergenGroups() != null) formulary.setAllergenGroups(request.getAllergenGroups());
        if (request.getIsHighAlert() != null) formulary.setHighAlert(request.getIsHighAlert());
        if (request.getRequiresDoubleCheck() != null) formulary.setRequiresDoubleCheck(request.getRequiresDoubleCheck());
        if (request.getBlackBoxWarning() != null) formulary.setBlackBoxWarning(request.getBlackBoxWarning());
        if (request.getPregnancyCategory() != null) formulary.setPregnancyCategory(request.getPregnancyCategory());
        if (request.getIsOnReml() != null) formulary.setOnReml(request.getIsOnReml());

        if (request.getHospitalId() != null) {
            Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());
            formulary.setHospital(hospital);
        }

        formulary = formularyRepository.save(formulary);

        log.info("Formulary entry updated: {} (id: {})", formulary.getGenericName(), id);

        return MedicationSafetyMapper.toFormularyResponse(formulary);
    }

    // ====================================================================
    // ALERT GENERATION
    // ====================================================================

    private void generateAlertsForSafetyResult(
            MedicationSafetyResult result,
            Visit visit,
            MedicationAdministration medication) {

        if (result.overallSafe()) {
            return;
        }

        // CRITICAL alerts — block administration
        if (!result.blockers().isEmpty()) {
            String blockersText = String.join("; ", result.blockers());
            ClinicalAlert alert = ClinicalAlert.builder()
                    .visit(visit)
                    .alertType(AlertType.MEDICATION_SAFETY_BLOCK)
                    .severity(AlertSeverity.CRITICAL)
                    .title("MEDICATION SAFETY BLOCK: " + medication.getDrugName())
                    .message("Medication administration BLOCKED for " + medication.getDrugName()
                            + ". Critical safety issues: " + blockersText
                            + ". Override required before administration.")
                    .autoGenerated(true)
                    .build();
            clinicalAlertRepository.save(alert);

            log.warn("CRITICAL medication safety alert generated for drug '{}' on visit {}",
                    medication.getDrugName(), visit.getId());
        }

        // HIGH alerts — warning
        if (!result.warnings().isEmpty()) {
            String warningsText = String.join("; ", result.warnings());
            ClinicalAlert alert = ClinicalAlert.builder()
                    .visit(visit)
                    .alertType(AlertType.MEDICATION_SAFETY_WARNING)
                    .severity(AlertSeverity.HIGH)
                    .title("MEDICATION SAFETY WARNING: " + medication.getDrugName())
                    .message("Medication safety warnings for " + medication.getDrugName()
                            + ": " + warningsText)
                    .autoGenerated(true)
                    .build();
            clinicalAlertRepository.save(alert);

            log.warn("HIGH medication safety alert generated for drug '{}' on visit {}",
                    medication.getDrugName(), visit.getId());
        }
    }
}
