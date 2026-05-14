package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.patient.dto.PatientChronicConditionResponse;
import com.smartTriage.smartTriage_server.module.patient.dto.RecordChronicConditionRequest;
import com.smartTriage.smartTriage_server.module.patient.dto.ResolveChronicConditionRequest;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.entity.PatientChronicCondition;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientChronicConditionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for structured patient chronic conditions. Same shape as
 * {@code PatientAllergyService}: list / record (idempotent against
 * duplicate names) / resolve (soft transition that keeps history).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatientChronicConditionService {

    private final PatientChronicConditionRepository conditionRepository;
    private final PatientService patientService;

    // ====================================================================
    // QUERIES
    // ====================================================================

    public List<PatientChronicConditionResponse> listActiveForPatient(UUID patientId) {
        patientService.findPatientOrThrow(patientId);
        return conditionRepository.findActiveByPatientId(patientId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<PatientChronicConditionResponse> listHistoryForPatient(UUID patientId) {
        patientService.findPatientOrThrow(patientId);
        return conditionRepository.findAllByPatientIdIncludingResolved(patientId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ====================================================================
    // RECORD
    // ====================================================================

    @Transactional
    public PatientChronicConditionResponse record(UUID patientId, RecordChronicConditionRequest request) {
        Patient patient = patientService.findPatientOrThrow(patientId);

        String conditionName = request.getConditionName() != null
                ? request.getConditionName().trim() : null;
        if (conditionName == null || conditionName.isBlank()) {
            throw new ClinicalBusinessException("Condition name is required");
        }

        // Idempotency — surface existing duplicate so a double-click
        // or retry doesn't create two rows for the same condition.
        var existing = conditionRepository.findActiveDuplicate(patientId, conditionName);
        if (existing.isPresent()) {
            log.info("Chronic condition '{}' already on file for patient {} — returning existing",
                    conditionName, patientId);
            return toResponse(existing.get());
        }

        PatientChronicCondition condition = PatientChronicCondition.builder()
                .patient(patient)
                .conditionName(conditionName)
                .conditionCode(request.getConditionCode())
                .status(request.getStatus() != null ? request.getStatus() : ChronicConditionStatus.ACTIVE)
                .notes(request.getNotes())
                .onsetDate(request.getOnsetDate())
                .recordedByName(request.getRecordedByName())
                .build();

        condition = conditionRepository.save(condition);
        log.info("Chronic condition recorded for patient {} — name:'{}' code:{} status:{}",
                patientId, condition.getConditionName(), condition.getConditionCode(),
                condition.getStatus());
        return toResponse(condition);
    }

    // ====================================================================
    // RESOLVE
    // ====================================================================

    @Transactional
    public PatientChronicConditionResponse resolve(UUID conditionId, ResolveChronicConditionRequest request) {
        PatientChronicCondition condition = conditionRepository.findByIdAndIsActiveTrue(conditionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "PatientChronicCondition", "id", conditionId));

        if (condition.getStatus() == ChronicConditionStatus.RESOLVED) {
            throw new ClinicalBusinessException(
                    "Condition '" + condition.getConditionName() + "' is already resolved");
        }

        condition.setStatus(ChronicConditionStatus.RESOLVED);
        condition.setResolvedAt(Instant.now());
        condition.setResolvedByName(request.getResolvedByName());
        condition.setResolveReason(request.getReason());

        condition = conditionRepository.save(condition);
        log.warn("Chronic condition RESOLVED — id:{} name:'{}' by:{} reason:'{}'",
                condition.getId(), condition.getConditionName(),
                condition.getResolvedByName(), condition.getResolveReason());

        return toResponse(condition);
    }

    // ====================================================================
    // MAPPING
    // ====================================================================

    private PatientChronicConditionResponse toResponse(PatientChronicCondition c) {
        return PatientChronicConditionResponse.builder()
                .id(c.getId())
                .patientId(c.getPatient() != null ? c.getPatient().getId() : null)
                .conditionCode(c.getConditionCode())
                .conditionName(c.getConditionName())
                .status(c.getStatus())
                .statusLabel(c.getStatus() != null ? c.getStatus().getLabel() : null)
                .notes(c.getNotes())
                .onsetDate(c.getOnsetDate())
                .recordedByName(c.getRecordedByName())
                .recordedAt(c.getRecordedAt())
                .resolvedByName(c.getResolvedByName())
                .resolvedAt(c.getResolvedAt())
                .resolveReason(c.getResolveReason())
                .build();
    }
}
