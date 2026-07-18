package com.smartTriage.smartTriage_server.module.clinical.service;

import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.clinical.dto.AmendDiagnosisRequest;
import com.smartTriage.smartTriage_server.module.clinical.dto.CreateDiagnosisRequest;
import com.smartTriage.smartTriage_server.module.clinical.dto.DiagnosisResponse;
import com.smartTriage.smartTriage_server.module.clinical.entity.Diagnosis;
import com.smartTriage.smartTriage_server.module.clinical.mapper.ClinicalMapper;
import com.smartTriage.smartTriage_server.module.clinical.repository.DiagnosisRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Diagnosis service — manages provisional, confirmed, differential, and working
 * diagnoses throughout an ED visit.
 *
 * The Rwanda triage forms include diagnosis documentation.
 * Multiple diagnoses can coexist per visit (differential diagnosis list).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiagnosisService {

    private final DiagnosisRepository diagnosisRepository;
    private final VisitService visitService;

    @Transactional
    public DiagnosisResponse createDiagnosis(CreateDiagnosisRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());

        // If marking as primary, clear existing primary diagnosis for this visit
        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            Optional<Diagnosis> existingPrimary = diagnosisRepository
                    .findByVisitIdAndIsPrimaryTrueAndIsActiveTrue(request.getVisitId());
            existingPrimary.ifPresent(d -> {
                d.setIsPrimary(false);
                diagnosisRepository.save(d);
            });
        }

        Diagnosis diagnosis = Diagnosis.builder()
                .visit(visit)
                .diagnosisType(request.getDiagnosisType())
                .icdCode(request.getIcdCode())
                .description(request.getDescription())
                .diagnosedByName(request.getDiagnosedByName())
                .diagnosedAt(Instant.now())
                .isPrimary(Boolean.TRUE.equals(request.getIsPrimary()))
                .notes(request.getNotes())
                .build();

        diagnosis = diagnosisRepository.save(diagnosis);

        log.info("Diagnosis created for visit {} — type:{} desc:'{}' icd:{}",
                visit.getVisitNumber(), diagnosis.getDiagnosisType(),
                diagnosis.getDescription(), diagnosis.getIcdCode());

        return ClinicalMapper.toResponse(diagnosis);
    }

    @Transactional
    public DiagnosisResponse updateDiagnosis(UUID diagnosisId, CreateDiagnosisRequest request) {
        Diagnosis diagnosis = findDiagnosisOrThrow(diagnosisId);

        // If changing to primary, clear existing primary
        if (Boolean.TRUE.equals(request.getIsPrimary()) && !Boolean.TRUE.equals(diagnosis.getIsPrimary())) {
            Optional<Diagnosis> existingPrimary = diagnosisRepository
                    .findByVisitIdAndIsPrimaryTrueAndIsActiveTrue(diagnosis.getVisit().getId());
            existingPrimary.ifPresent(d -> {
                d.setIsPrimary(false);
                diagnosisRepository.save(d);
            });
        }

        diagnosis.setDiagnosisType(request.getDiagnosisType());
        diagnosis.setIcdCode(request.getIcdCode());
        diagnosis.setDescription(request.getDescription());
        diagnosis.setDiagnosedByName(request.getDiagnosedByName());
        diagnosis.setIsPrimary(Boolean.TRUE.equals(request.getIsPrimary()));
        diagnosis.setNotes(request.getNotes());

        diagnosis = diagnosisRepository.save(diagnosis);

        log.info("Diagnosis updated — id:{} type:{} desc:'{}'",
                diagnosis.getId(), diagnosis.getDiagnosisType(), diagnosis.getDescription());

        return ClinicalMapper.toResponse(diagnosis);
    }

    /**
     * Amend (edit) a diagnosis WITHOUT losing the original. Creates a new version
     * linked back to the root original, records the reason + who/when, and
     * soft-supersedes the prior version so current-state reads show only the
     * latest while the full chain stays retrievable via {@link #getHistory}.
     */
    @Transactional
    public DiagnosisResponse amendDiagnosis(UUID diagnosisId, AmendDiagnosisRequest request) {
        Diagnosis current = findDiagnosisOrThrow(diagnosisId);
        // Always link amendments to the FIRST version so the whole chain shares one root.
        Diagnosis root = current.getOriginalDiagnosis() != null
                ? current.getOriginalDiagnosis() : current;

        // If the amended version is primary, demote any other active primary on the visit.
        if (Boolean.TRUE.equals(request.getIsPrimary())) {
            diagnosisRepository.findByVisitIdAndIsPrimaryTrueAndIsActiveTrue(current.getVisit().getId())
                    .filter(d -> !d.getId().equals(current.getId()))
                    .ifPresent(d -> { d.setIsPrimary(false); diagnosisRepository.save(d); });
        }

        Diagnosis amended = Diagnosis.builder()
                .visit(current.getVisit())
                .diagnosisType(request.getDiagnosisType())
                .icdCode(request.getIcdCode())
                .description(request.getDescription())
                .diagnosedByName(request.getDiagnosedByName())
                // Preserve the original clinical diagnosis time; amendedAt marks the edit.
                .diagnosedAt(current.getDiagnosedAt())
                .isPrimary(Boolean.TRUE.equals(request.getIsPrimary()))
                .notes(request.getNotes())
                .originalDiagnosis(root)
                .isAmendment(true)
                .amendmentReason(request.getAmendmentReason())
                .amendedAt(Instant.now())
                .build();
        amended = diagnosisRepository.save(amended);

        // Supersede the prior version — soft-delete keeps it (and its history) on file.
        current.softDelete();
        diagnosisRepository.save(current);

        log.info("Diagnosis amended — new id:{} supersedes:{} root:{} reason:'{}'",
                amended.getId(), current.getId(), root.getId(), request.getAmendmentReason());

        return ClinicalMapper.toResponse(amended);
    }

    /** Full version history for a diagnosis (root + every amendment), oldest first. */
    public List<DiagnosisResponse> getHistory(UUID diagnosisId) {
        Diagnosis current = findDiagnosisOrThrow(diagnosisId);
        Diagnosis root = current.getOriginalDiagnosis() != null
                ? current.getOriginalDiagnosis() : current;
        return diagnosisRepository.findVersionChain(root.getId())
                .stream().map(ClinicalMapper::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public void deleteDiagnosis(UUID diagnosisId) {
        Diagnosis diagnosis = findDiagnosisOrThrow(diagnosisId);
        diagnosis.softDelete();
        diagnosisRepository.save(diagnosis);
        log.info("Diagnosis soft-deleted — id:{}", diagnosisId);
    }

    public Page<DiagnosisResponse> getDiagnosesByVisit(UUID visitId, Pageable pageable) {
        return diagnosisRepository
                .findByVisitIdAndIsActiveTrueOrderByDiagnosedAtDesc(visitId, pageable)
                .map(ClinicalMapper::toResponse);
    }

    public List<DiagnosisResponse> getAllDiagnosesForVisit(UUID visitId) {
        return diagnosisRepository
                .findByVisitIdAndIsActiveTrueOrderByDiagnosedAtAsc(visitId)
                .stream()
                .map(ClinicalMapper::toResponse)
                .collect(Collectors.toList());
    }

    public List<DiagnosisResponse> getDiagnosesByType(UUID visitId, DiagnosisType type) {
        return diagnosisRepository
                .findByVisitIdAndDiagnosisTypeAndIsActiveTrueOrderByDiagnosedAtDesc(visitId, type)
                .stream()
                .map(ClinicalMapper::toResponse)
                .collect(Collectors.toList());
    }

    public DiagnosisResponse getDiagnosis(UUID diagnosisId) {
        return ClinicalMapper.toResponse(findDiagnosisOrThrow(diagnosisId));
    }

    public Diagnosis findDiagnosisOrThrow(UUID id) {
        return diagnosisRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Diagnosis", "id", id));
    }
}
