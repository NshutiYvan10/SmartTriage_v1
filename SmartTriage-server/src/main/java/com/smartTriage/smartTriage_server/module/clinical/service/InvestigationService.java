package com.smartTriage.smartTriage_server.module.clinical.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.InvestigationStatus;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.clinical.dto.InvestigationResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.OrderInvestigationRequest;
import com.smartTriage.smartTriage_server.module.clinical.dto.RecordInvestigationResultRequest;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.module.clinical.mapper.ClinicalMapper;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
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
 * Investigation service — manages diagnostic investigations throughout an ED
 * visit.
 *
 * Handles the full investigation lifecycle:
 * ORDERED → SPECIMEN_COLLECTED → IN_PROGRESS → RESULTED / CANCELLED
 *
 * Supports lab tests, radiology, ECG, point-of-care tests, etc.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvestigationService {

        private final InvestigationRepository investigationRepository;
        private final VisitService visitService;
        private final ClinicalAlertRepository clinicalAlertRepository;

        // ====================================================================
        // ORDER
        // ====================================================================

        @Transactional
        public InvestigationResponse orderInvestigation(OrderInvestigationRequest request) {
                Visit visit = visitService.findVisitOrThrow(request.getVisitId());

                Investigation investigation = Investigation.builder()
                                .visit(visit)
                                .investigationType(request.getInvestigationType())
                                .testName(request.getTestName())
                                .orderedByName(request.getOrderedByName())
                                .orderedAt(Instant.now())
                                .priority(request.getPriority() != null ? request.getPriority() : "ROUTINE")
                                .status(InvestigationStatus.ORDERED)
                                .notes(request.getNotes())
                                .build();

                investigation = investigationRepository.save(investigation);

                log.info("Investigation ordered for visit {} — type:{} test:'{}' priority:{}",
                                visit.getVisitNumber(), investigation.getInvestigationType(),
                                investigation.getTestName(), investigation.getPriority());

                return ClinicalMapper.toResponse(investigation);
        }

        // ====================================================================
        // STATUS TRANSITIONS
        // ====================================================================

        @Transactional
        public InvestigationResponse markSpecimenCollected(UUID investigationId) {
                Investigation investigation = findInvestigationOrThrow(investigationId);

                if (investigation.getStatus() != InvestigationStatus.ORDERED) {
                        throw new ClinicalBusinessException(
                                        "Cannot mark specimen collected for investigation in status: "
                                                        + investigation.getStatus());
                }

                investigation.setSpecimenCollectedAt(Instant.now());
                investigation.setStatus(InvestigationStatus.SPECIMEN_COLLECTED);
                investigation = investigationRepository.save(investigation);

                log.info("Specimen collected — investigation:{} test:'{}'",
                                investigation.getId(), investigation.getTestName());

                return ClinicalMapper.toResponse(investigation);
        }

        @Transactional
        public InvestigationResponse markInProgress(UUID investigationId) {
                Investigation investigation = findInvestigationOrThrow(investigationId);

                if (investigation.getStatus() != InvestigationStatus.ORDERED
                                && investigation.getStatus() != InvestigationStatus.SPECIMEN_COLLECTED) {
                        throw new ClinicalBusinessException(
                                        "Cannot mark in progress for investigation in status: "
                                                        + investigation.getStatus());
                }

                investigation.setStatus(InvestigationStatus.IN_PROGRESS);
                investigation = investigationRepository.save(investigation);

                log.info("Investigation in progress — id:{} test:'{}'",
                                investigation.getId(), investigation.getTestName());

                return ClinicalMapper.toResponse(investigation);
        }

        @Transactional
        public InvestigationResponse recordResult(RecordInvestigationResultRequest request) {
                Investigation investigation = findInvestigationOrThrow(request.getInvestigationId());

                if (investigation.getStatus() == InvestigationStatus.CANCELLED
                                || investigation.getStatus() == InvestigationStatus.RESULTED) {
                        throw new ClinicalBusinessException(
                                        "Cannot record result for investigation in status: "
                                                        + investigation.getStatus());
                }

                investigation.setResultedAt(Instant.now());
                investigation.setResult(request.getResult());
                investigation.setIsAbnormal(Boolean.TRUE.equals(request.getIsAbnormal()));
                investigation.setIsCritical(Boolean.TRUE.equals(request.getIsCritical()));
                investigation.setStatus(InvestigationStatus.RESULTED);

                if (request.getNotes() != null && !request.getNotes().isBlank()) {
                        String existingNotes = investigation.getNotes() != null
                                        ? investigation.getNotes() + " | "
                                        : "";
                        investigation.setNotes(existingNotes + "Result: " + request.getNotes());
                }

                investigation = investigationRepository.save(investigation);

                // Generate INVESTIGATION_RESULTED alert so the ordering doctor is notified
                generateResultAlert(investigation);

                log.info("Investigation resulted — id:{} test:'{}' abnormal:{} critical:{}",
                                investigation.getId(), investigation.getTestName(),
                                investigation.getIsAbnormal(), investigation.getIsCritical());

                return ClinicalMapper.toResponse(investigation);
        }

        @Transactional
        public InvestigationResponse cancelInvestigation(UUID investigationId, String reason) {
                Investigation investigation = findInvestigationOrThrow(investigationId);

                if (investigation.getStatus() == InvestigationStatus.RESULTED) {
                        throw new ClinicalBusinessException(
                                        "Cannot cancel an already-resulted investigation.");
                }

                investigation.setStatus(InvestigationStatus.CANCELLED);
                if (reason != null && !reason.isBlank()) {
                        String existingNotes = investigation.getNotes() != null
                                        ? investigation.getNotes() + " | "
                                        : "";
                        investigation.setNotes(existingNotes + "CANCELLED: " + reason);
                }

                investigation = investigationRepository.save(investigation);

                log.info("Investigation cancelled — id:{} test:'{}' reason:'{}'",
                                investigation.getId(), investigation.getTestName(), reason);

                return ClinicalMapper.toResponse(investigation);
        }

        // ====================================================================
        // QUERIES
        // ====================================================================

        public Page<InvestigationResponse> getInvestigationsByVisit(UUID visitId, Pageable pageable) {
                return investigationRepository
                                .findByVisitIdAndIsActiveTrueOrderByOrderedAtDesc(visitId, pageable)
                                .map(ClinicalMapper::toResponse);
        }

        public List<InvestigationResponse> getAllInvestigationsForVisit(UUID visitId) {
                return investigationRepository
                                .findByVisitIdAndIsActiveTrueOrderByOrderedAtAsc(visitId)
                                .stream()
                                .map(ClinicalMapper::toResponse)
                                .collect(Collectors.toList());
        }

        public List<InvestigationResponse> getInvestigationsByType(UUID visitId, InvestigationType type) {
                return investigationRepository
                                .findByVisitIdAndInvestigationTypeAndIsActiveTrueOrderByOrderedAtDesc(visitId, type)
                                .stream()
                                .map(ClinicalMapper::toResponse)
                                .collect(Collectors.toList());
        }

        public List<InvestigationResponse> getPendingInvestigations(UUID visitId) {
                return investigationRepository
                                .findByVisitIdAndStatusAndIsActiveTrueOrderByOrderedAtAsc(
                                                visitId, InvestigationStatus.ORDERED)
                                .stream()
                                .map(ClinicalMapper::toResponse)
                                .collect(Collectors.toList());
        }

        public InvestigationResponse getInvestigation(UUID investigationId) {
                return ClinicalMapper.toResponse(findInvestigationOrThrow(investigationId));
        }

        public Investigation findInvestigationOrThrow(UUID id) {
                return investigationRepository.findByIdAndIsActiveTrue(id)
                                .orElseThrow(() -> new ResourceNotFoundException("Investigation", "id", id));
        }

        // ====================================================================
        // ALERT GENERATION
        // ====================================================================

        /**
         * Generate an INVESTIGATION_RESULTED alert when an investigation result is
         * recorded.
         * Severity: HIGH for critical results, MEDIUM for abnormal, LOW for normal.
         */
        private void generateResultAlert(Investigation investigation) {
                try {
                        Visit visit = investigation.getVisit();
                        AlertSeverity severity = investigation.getIsCritical() ? AlertSeverity.CRITICAL
                                        : investigation.getIsAbnormal() ? AlertSeverity.HIGH
                                                        : AlertSeverity.MEDIUM;

                        String prefix = investigation.getIsCritical() ? "CRITICAL "
                                        : investigation.getIsAbnormal() ? "Abnormal " : "";
                        String title = prefix + "Result: " + investigation.getTestName();
                        String message = String.format("Investigation '%s' result is now available for visit %s.%s",
                                        investigation.getTestName(),
                                        visit.getVisitNumber(),
                                        investigation.getIsCritical() ? " CRITICAL VALUE — immediate review required."
                                                        : investigation.getIsAbnormal() ? " Abnormal value detected."
                                                                        : "");

                        // Derive zone from triage category
                        EdZone zone = visit.getCurrentTriageCategory() != null
                                        ? EdZone.fromTriageCategory(visit.getCurrentTriageCategory())
                                        : null;

                        ClinicalAlert alert = ClinicalAlert.builder()
                                        .visit(visit)
                                        .alertType(AlertType.INVESTIGATION_RESULTED)
                                        .severity(severity)
                                        .title(title)
                                        .message(message)
                                        .targetZone(zone)
                                        .build();

                        clinicalAlertRepository.save(alert);
                        log.info("INVESTIGATION_RESULTED alert created for visit {} — test: '{}' severity: {}",
                                        visit.getVisitNumber(), investigation.getTestName(), severity);
                } catch (Exception e) {
                        // Alert generation should never block the investigation result recording
                        log.error("Failed to generate result alert for investigation {}: {}",
                                        investigation.getId(), e.getMessage());
                }
        }
}
