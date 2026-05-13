package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.enums.AllergyVerificationStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import com.smartTriage.smartTriage_server.module.medsafety.repository.DrugFormularyRepository;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientAllergyResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RecordAllergyRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.RefuteAllergyRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PatientAllergy;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientAllergyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for structured patient allergies.
 *
 * <p>Replaces the legacy free-text {@code Patient.knownAllergies}
 * model with severity-graded, FK-linked records. The legacy column
 * stays as a fallback (read by {@code MedicationSafetyEngine} when
 * no structured rows exist for the patient).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientAllergyService {

    private final PatientAllergyRepository allergyRepository;
    private final PatientService patientService;
    private final DrugFormularyRepository formularyRepository;

    // ====================================================================
    // QUERIES
    // ====================================================================

    /**
     * Active (non-refuted) allergies for a patient. This is what the
     * prescribe-time safety dialog and the profile panel render.
     */
    public List<PatientAllergyResponse> listActiveForPatient(UUID patientId) {
        // Defensive: confirm patient exists so the API surface returns
        // 404 not an empty list when the patient id is wrong.
        patientService.findPatientOrThrow(patientId);
        return allergyRepository.findActiveByPatientId(patientId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Full audit history including refuted rows. Drives the
     * "Allergy history" expanded view in the profile panel.
     */
    public List<PatientAllergyResponse> listHistoryForPatient(UUID patientId) {
        patientService.findPatientOrThrow(patientId);
        return allergyRepository.findAllByPatientIdIncludingRefuted(patientId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Direct entity accessor for the {@link
     * com.smartTriage.smartTriage_server.module.medsafety.engine.MedicationSafetyEngine}
     * — returns the raw entities so the engine can read the
     * formulary FK and severity without paying for DTO mapping
     * inside its critical path.
     */
    public List<PatientAllergy> findActiveEntitiesForPatient(UUID patientId) {
        return allergyRepository.findActiveByPatientId(patientId);
    }

    // ====================================================================
    // RECORD
    // ====================================================================

    @Transactional
    public PatientAllergyResponse record(UUID patientId, RecordAllergyRequest request) {
        Patient patient = patientService.findPatientOrThrow(patientId);

        String allergenName = request.getAllergenName() != null
                ? request.getAllergenName().trim()
                : null;
        if (allergenName == null || allergenName.isBlank()) {
            throw new ClinicalBusinessException("Allergen name is required");
        }

        // Idempotency — surface the existing row rather than create a
        // duplicate when the same allergen is recorded twice in quick
        // succession (UI double-click, retry on flaky network, etc.).
        var existing = allergyRepository.findActiveDuplicate(patientId, allergenName);
        if (existing.isPresent()) {
            log.info("Allergy '{}' already on file for patient {} — returning existing row",
                    allergenName, patientId);
            return toResponse(existing.get());
        }

        DrugFormulary formulary = null;
        if (request.getAllergenFormularyId() != null) {
            formulary = formularyRepository
                    .findByIdAndIsActiveTrue(request.getAllergenFormularyId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "DrugFormulary", "id", request.getAllergenFormularyId()));
        }

        PatientAllergy allergy = PatientAllergy.builder()
                .patient(patient)
                .allergenFormulary(formulary)
                .allergenName(allergenName)
                .severity(request.getSeverity())
                .reaction(request.getReaction())
                .onsetDate(request.getOnsetDate())
                .verificationStatus(request.getVerificationStatus() != null
                        ? request.getVerificationStatus()
                        : AllergyVerificationStatus.PATIENT_REPORTED)
                .recordedByName(request.getRecordedByName())
                .build();

        allergy = allergyRepository.save(allergy);

        log.info("Allergy recorded for patient {} — allergen:'{}' severity:{} status:{}",
                patientId, allergy.getAllergenName(), allergy.getSeverity(),
                allergy.getVerificationStatus());

        return toResponse(allergy);
    }

    // ====================================================================
    // REFUTE
    // ====================================================================

    @Transactional
    public PatientAllergyResponse refute(UUID allergyId, RefuteAllergyRequest request) {
        PatientAllergy allergy = allergyRepository.findByIdAndIsActiveTrue(allergyId)
                .orElseThrow(() -> new ResourceNotFoundException("PatientAllergy", "id", allergyId));

        if (allergy.getVerificationStatus() == AllergyVerificationStatus.REFUTED) {
            throw new ClinicalBusinessException(
                    "Allergy '" + allergy.getAllergenName() + "' is already refuted");
        }

        allergy.setVerificationStatus(AllergyVerificationStatus.REFUTED);
        allergy.setRefutedAt(Instant.now());
        allergy.setRefutedByName(request.getRefutedByName());
        allergy.setRefuteReason(request.getReason());

        allergy = allergyRepository.save(allergy);

        log.warn("Allergy REFUTED — id:{} allergen:'{}' by:{} reason:'{}'",
                allergy.getId(), allergy.getAllergenName(),
                allergy.getRefutedByName(), allergy.getRefuteReason());

        return toResponse(allergy);
    }

    // ====================================================================
    // MAPPING
    // ====================================================================

    private PatientAllergyResponse toResponse(PatientAllergy a) {
        return PatientAllergyResponse.builder()
                .id(a.getId())
                .patientId(a.getPatient() != null ? a.getPatient().getId() : null)
                .allergenFormularyId(a.getAllergenFormulary() != null
                        ? a.getAllergenFormulary().getId() : null)
                .allergenName(a.getAllergenName())
                .severity(a.getSeverity())
                .severityLabel(a.getSeverity() != null ? a.getSeverity().getLabel() : null)
                .reaction(a.getReaction())
                .onsetDate(a.getOnsetDate())
                .verificationStatus(a.getVerificationStatus())
                .verificationStatusLabel(a.getVerificationStatus() != null
                        ? a.getVerificationStatus().getLabel() : null)
                .recordedByName(a.getRecordedByName())
                .recordedAt(a.getRecordedAt())
                .refutedByName(a.getRefutedByName())
                .refutedAt(a.getRefutedAt())
                .refuteReason(a.getRefuteReason())
                .build();
    }
}
