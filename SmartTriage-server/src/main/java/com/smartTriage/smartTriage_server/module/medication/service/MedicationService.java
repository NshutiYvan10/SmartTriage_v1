package com.smartTriage.smartTriage_server.module.medication.service;

import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.medication.dto.AdministerMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.CountersignMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.dto.MedicationResponse;
import com.smartTriage.smartTriage_server.module.medication.dto.PrescribeMedicationRequest;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.mapper.MedicationMapper;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
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
 * Medication Administration Record (MAR) service.
 *
 * Handles the full lifecycle of medication entries as they appear on the
 * Rwanda national triage forms:
 *   1. Prescribe  → create entry with drug, dose, route, frequency
 *   2. Administer → record who gave it and when
 *   3. Countersign→ second clinician verification (patient-safety)
 *
 * Also supports holding, cancelling, and listing medications per visit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MedicationService {

    private final MedicationAdministrationRepository medicationRepository;
    private final VisitService visitService;

    // ====================================================================
    // PRESCRIBE
    // ====================================================================

    @Transactional
    public MedicationResponse prescribe(PrescribeMedicationRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());

        MedicationAdministration med = MedicationAdministration.builder()
                .visit(visit)
                .drugName(request.getDrugName())
                .dose(request.getDose())
                .route(request.getRoute())
                .frequency(request.getFrequency())
                .prescribedAt(Instant.now())
                .prescribedByName(request.getPrescribedByName())
                .status(MedicationStatus.PRESCRIBED)
                .notes(request.getNotes())
                .build();

        med = medicationRepository.save(med);

        log.info("Medication prescribed for visit {} — drug:{} dose:{} route:{} freq:{}",
                visit.getVisitNumber(), med.getDrugName(), med.getDose(),
                med.getRoute(), med.getFrequency());

        return MedicationMapper.toResponse(med);
    }

    // ====================================================================
    // ADMINISTER
    // ====================================================================

    @Transactional
    public MedicationResponse administer(UUID medicationId, AdministerMedicationRequest request) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        if (med.getStatus() != MedicationStatus.PRESCRIBED) {
            throw new ClinicalBusinessException(
                    "Cannot administer medication in status: " + med.getStatus()
                            + ". Only PRESCRIBED medications can be administered.");
        }

        med.setAdministeredAt(Instant.now());
        med.setAdministeredByName(request.getAdministeredByName());
        med.setStatus(MedicationStatus.ADMINISTERED);

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "Admin: " + request.getNotes());
        }

        med = medicationRepository.save(med);

        log.info("Medication administered — id:{} drug:{} visit:{}",
                med.getId(), med.getDrugName(), med.getVisit().getVisitNumber());

        return MedicationMapper.toResponse(med);
    }

    // ====================================================================
    // COUNTERSIGN
    // ====================================================================

    @Transactional
    public MedicationResponse countersign(UUID medicationId, CountersignMedicationRequest request) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        if (med.getStatus() != MedicationStatus.ADMINISTERED) {
            throw new ClinicalBusinessException(
                    "Cannot countersign medication in status: " + med.getStatus()
                            + ". Only ADMINISTERED medications can be countersigned.");
        }

        med.setCountersignedAt(Instant.now());
        med.setCountersignedByName(request.getCountersignedByName());

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "Countersign: " + request.getNotes());
        }

        med = medicationRepository.save(med);

        log.info("Medication countersigned — id:{} drug:{} by:{}",
                med.getId(), med.getDrugName(), med.getCountersignedByName());

        return MedicationMapper.toResponse(med);
    }

    // ====================================================================
    // STATUS CHANGES (Hold / Cancel)
    // ====================================================================

    @Transactional
    public MedicationResponse holdMedication(UUID medicationId, String reason) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        if (med.getStatus() != MedicationStatus.PRESCRIBED) {
            throw new ClinicalBusinessException(
                    "Only PRESCRIBED medications can be held. Current status: " + med.getStatus());
        }

        med.setStatus(MedicationStatus.HELD);
        if (reason != null && !reason.isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "HELD: " + reason);
        }

        med = medicationRepository.save(med);
        log.info("Medication held — id:{} drug:{} reason:{}", med.getId(), med.getDrugName(), reason);
        return MedicationMapper.toResponse(med);
    }

    @Transactional
    public MedicationResponse cancelMedication(UUID medicationId, String reason) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        med.setStatus(MedicationStatus.CANCELLED);
        if (reason != null && !reason.isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "CANCELLED: " + reason);
        }

        med = medicationRepository.save(med);
        log.info("Medication cancelled — id:{} drug:{} reason:{}", med.getId(), med.getDrugName(), reason);
        return MedicationMapper.toResponse(med);
    }

    @Transactional
    public MedicationResponse refuseMedication(UUID medicationId, String reason) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);

        if (med.getStatus() != MedicationStatus.PRESCRIBED) {
            throw new ClinicalBusinessException(
                    "Only PRESCRIBED medications can be refused. Current status: " + med.getStatus());
        }

        med.setStatus(MedicationStatus.REFUSED);
        if (reason != null && !reason.isBlank()) {
            String existingNotes = med.getNotes() != null ? med.getNotes() + " | " : "";
            med.setNotes(existingNotes + "REFUSED: " + reason);
        }

        med = medicationRepository.save(med);
        log.info("Medication refused — id:{} drug:{} reason:{}", med.getId(), med.getDrugName(), reason);
        return MedicationMapper.toResponse(med);
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public Page<MedicationResponse> getMedicationsByVisit(UUID visitId, Pageable pageable) {
        return medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtDesc(visitId, pageable)
                .map(MedicationMapper::toResponse);
    }

    public List<MedicationResponse> getAllMedicationsForVisit(UUID visitId) {
        return medicationRepository
                .findByVisitIdAndIsActiveTrueOrderByPrescribedAtAsc(visitId)
                .stream()
                .map(MedicationMapper::toResponse)
                .collect(Collectors.toList());
    }

    public MedicationResponse getMedication(UUID medicationId) {
        MedicationAdministration med = findMedicationOrThrow(medicationId);
        return MedicationMapper.toResponse(med);
    }

    // ====================================================================
    // INTERNAL
    // ====================================================================

    public MedicationAdministration findMedicationOrThrow(UUID id) {
        return medicationRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "MedicationAdministration", "id", id));
    }
}
