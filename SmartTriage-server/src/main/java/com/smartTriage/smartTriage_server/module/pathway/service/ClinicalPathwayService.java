package com.smartTriage.smartTriage_server.module.pathway.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.PathwayActivationStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.pathway.dto.*;
import com.smartTriage.smartTriage_server.module.pathway.engine.PathwayRecommendationEngine;
import com.smartTriage.smartTriage_server.module.pathway.entity.*;
import com.smartTriage.smartTriage_server.module.pathway.mapper.PathwayMapper;
import com.smartTriage.smartTriage_server.module.pathway.repository.*;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Clinical Pathway Service — manages evidence-based clinical pathway execution.
 *
 * Supports:
 *   - Pathway recommendation based on triage findings
 *   - Pathway activation, step completion, skip, abandon, complete
 *   - Progress tracking with overdue step detection
 *   - Compliance alerting for mandatory overdue steps
 *   - Admin pathway creation
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalPathwayService {

    private final ClinicalPathwayRepository pathwayRepository;
    private final PathwayStepRepository stepRepository;
    private final PathwayActivationRepository activationRepository;
    private final PathwayStepCompletionRepository completionRepository;
    private final VisitService visitService;
    private final TriageRecordRepository triageRecordRepository;
    private final PathwayRecommendationEngine recommendationEngine;
    private final ClinicalAlertRepository alertRepository;

    // ====================================================================
    // RECOMMEND PATHWAYS
    // ====================================================================

    public List<PathwayRecommendation> recommendPathways(UUID visitId) {
        Visit visit = visitService.findVisitOrThrow(visitId);
        TriageRecord triage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId)
                .orElse(null);
        return recommendationEngine.recommendPathways(visit, triage);
    }

    // ====================================================================
    // ACTIVATE PATHWAY
    // ====================================================================

    @Transactional
    public PathwayActivationResponse activatePathway(UUID visitId, UUID pathwayId, String activatedByName, String notes) {
        Visit visit = visitService.findVisitOrThrow(visitId);
        ClinicalPathway pathway = findPathwayOrThrow(pathwayId);

        // Check if already active for this visit
        if (activationRepository.existsByVisitIdAndPathwayIdAndStatusAndIsActiveTrue(
                visitId, pathwayId, PathwayActivationStatus.ACTIVE)) {
            throw new ClinicalBusinessException(
                    "Pathway '" + pathway.getPathwayName() + "' is already active for this visit.");
        }

        PathwayActivation activation = PathwayActivation.builder()
                .visit(visit)
                .pathway(pathway)
                .activatedAt(Instant.now())
                .activatedByName(activatedByName)
                .status(PathwayActivationStatus.ACTIVE)
                .notes(notes)
                .build();

        activation = activationRepository.save(activation);

        log.info("Pathway activated — visit:{} pathway:{} ({}) by:{}",
                visit.getVisitNumber(), pathway.getPathwayCode(),
                pathway.getPathwayName(), activatedByName);

        return PathwayMapper.toResponse(activation);
    }

    // ====================================================================
    // COMPLETE STEP
    // ====================================================================

    @Transactional
    public PathwayStepCompletionResponse completeStep(UUID activationId, UUID stepId, CompleteStepRequest request) {
        PathwayActivation activation = findActivationOrThrow(activationId);
        PathwayStep step = findStepOrThrow(stepId);

        validateActivationActive(activation);

        // Check if already completed
        if (completionRepository.existsByActivationIdAndStepIdAndIsActiveTrue(activationId, stepId)) {
            throw new ClinicalBusinessException("Step '" + step.getStepTitle() + "' has already been completed.");
        }

        int minutesSinceActivation = (int) Duration.between(activation.getActivatedAt(), Instant.now()).toMinutes();

        PathwayStepCompletion completion = PathwayStepCompletion.builder()
                .activation(activation)
                .step(step)
                .completedAt(Instant.now())
                .completedByName(request.getCompletedByName())
                .wasSkipped(false)
                .notes(request.getNotes())
                .timeToCompleteMinutes(minutesSinceActivation)
                .build();

        completion = completionRepository.save(completion);

        log.info("Pathway step completed — activation:{} step:{} '{}' by:{} ({}min)",
                activationId, stepId, step.getStepTitle(),
                request.getCompletedByName(), minutesSinceActivation);

        return PathwayMapper.toResponse(completion);
    }

    // ====================================================================
    // SKIP STEP
    // ====================================================================

    @Transactional
    public PathwayStepCompletionResponse skipStep(UUID activationId, UUID stepId, String reason, String completedByName) {
        PathwayActivation activation = findActivationOrThrow(activationId);
        PathwayStep step = findStepOrThrow(stepId);

        validateActivationActive(activation);

        if (completionRepository.existsByActivationIdAndStepIdAndIsActiveTrue(activationId, stepId)) {
            throw new ClinicalBusinessException("Step '" + step.getStepTitle() + "' has already been completed or skipped.");
        }

        int minutesSinceActivation = (int) Duration.between(activation.getActivatedAt(), Instant.now()).toMinutes();

        PathwayStepCompletion completion = PathwayStepCompletion.builder()
                .activation(activation)
                .step(step)
                .completedAt(Instant.now())
                .completedByName(completedByName)
                .wasSkipped(true)
                .skipReason(reason)
                .timeToCompleteMinutes(minutesSinceActivation)
                .build();

        completion = completionRepository.save(completion);

        log.info("Pathway step skipped — activation:{} step:{} '{}' reason:'{}' by:{}",
                activationId, stepId, step.getStepTitle(), reason, completedByName);

        return PathwayMapper.toResponse(completion);
    }

    // ====================================================================
    // COMPLETE PATHWAY
    // ====================================================================

    @Transactional
    public PathwayActivationResponse completePathway(UUID activationId) {
        PathwayActivation activation = findActivationOrThrow(activationId);
        validateActivationActive(activation);

        activation.setStatus(PathwayActivationStatus.COMPLETED);
        activation.setCompletedAt(Instant.now());

        activation = activationRepository.save(activation);

        log.info("Pathway completed — activation:{} pathway:{}",
                activationId, activation.getPathway().getPathwayCode());

        return PathwayMapper.toResponse(activation);
    }

    // ====================================================================
    // ABANDON PATHWAY
    // ====================================================================

    @Transactional
    public PathwayActivationResponse abandonPathway(UUID activationId, String reason) {
        PathwayActivation activation = findActivationOrThrow(activationId);
        validateActivationActive(activation);

        activation.setStatus(PathwayActivationStatus.ABANDONED);
        activation.setCompletedAt(Instant.now());
        activation.setDeviationReason(reason);

        activation = activationRepository.save(activation);

        log.info("Pathway abandoned — activation:{} pathway:{} reason:'{}'",
                activationId, activation.getPathway().getPathwayCode(), reason);

        return PathwayMapper.toResponse(activation);
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    public List<PathwayActivationResponse> getActivePathways(UUID visitId) {
        return activationRepository
                .findByVisitIdAndStatusAndIsActiveTrueOrderByActivatedAtDesc(
                        visitId, PathwayActivationStatus.ACTIVE)
                .stream()
                .map(PathwayMapper::toResponse)
                .collect(Collectors.toList());
    }

    public PathwayProgressResponse getPathwayProgress(UUID activationId) {
        PathwayActivation activation = findActivationOrThrow(activationId);
        ClinicalPathway pathway = activation.getPathway();

        List<PathwayStep> allSteps = stepRepository
                .findByPathwayIdAndIsActiveTrueOrderByStepOrderAsc(pathway.getId());

        List<PathwayStepCompletion> completions = completionRepository
                .findByActivationIdAndIsActiveTrueOrderByCompletedAtAsc(activationId);

        Map<UUID, PathwayStepCompletion> completionMap = completions.stream()
                .collect(Collectors.toMap(c -> c.getStep().getId(), Function.identity()));

        int completedCount = 0;
        int skippedCount = 0;
        int pendingCount = 0;
        List<PathwayProgressResponse.StepProgress> stepProgresses = new ArrayList<>();
        List<String> overdueSteps = new ArrayList<>();

        long minutesSinceActivation = Duration.between(activation.getActivatedAt(), Instant.now()).toMinutes();

        for (PathwayStep step : allSteps) {
            PathwayStepCompletion completion = completionMap.get(step.getId());
            String status;

            if (completion != null) {
                if (completion.isWasSkipped()) {
                    status = "SKIPPED";
                    skippedCount++;
                } else {
                    status = "COMPLETED";
                    completedCount++;
                }
            } else {
                // Check if overdue (2x timeframe)
                if (step.getTimeframeMinutes() != null && step.isMandatory()
                        && minutesSinceActivation > step.getTimeframeMinutes() * 2L) {
                    status = "OVERDUE";
                    overdueSteps.add(step.getStepTitle());
                    pendingCount++;
                } else {
                    status = "PENDING";
                    pendingCount++;
                }
            }

            stepProgresses.add(PathwayProgressResponse.StepProgress.builder()
                    .stepId(step.getId())
                    .stepOrder(step.getStepOrder())
                    .stepTitle(step.getStepTitle())
                    .category(step.getCategory())
                    .isMandatory(step.isMandatory())
                    .timeframeMinutes(step.getTimeframeMinutes())
                    .status(status)
                    .completedAt(completion != null ? completion.getCompletedAt() : null)
                    .completedByName(completion != null ? completion.getCompletedByName() : null)
                    .timeToCompleteMinutes(completion != null ? completion.getTimeToCompleteMinutes() : null)
                    .build());
        }

        double completionPercentage = allSteps.isEmpty() ? 0.0
                : ((double) (completedCount + skippedCount) / allSteps.size()) * 100.0;

        // Generate compliance alert for overdue mandatory steps
        if (!overdueSteps.isEmpty()) {
            generateComplianceAlert(activation, overdueSteps);
        }

        return PathwayProgressResponse.builder()
                .activationId(activationId)
                .pathwayId(pathway.getId())
                .pathwayName(pathway.getPathwayName())
                .status(activation.getStatus())
                .activatedAt(activation.getActivatedAt())
                .totalSteps(allSteps.size())
                .completedSteps(completedCount)
                .skippedSteps(skippedCount)
                .pendingSteps(pendingCount)
                .completionPercentage(Math.round(completionPercentage * 10.0) / 10.0)
                .steps(stepProgresses)
                .overdueSteps(overdueSteps)
                .build();
    }

    public List<ClinicalPathwayResponse> getAllPathways() {
        return pathwayRepository.findAllByIsActiveTrueOrderByPathwayNameAsc()
                .stream()
                .map(PathwayMapper::toResponse)
                .collect(Collectors.toList());
    }

    public List<PathwayStepResponse> getStepsForPathway(UUID pathwayId) {
        return stepRepository.findByPathwayIdAndIsActiveTrueOrderByStepOrderAsc(pathwayId)
                .stream()
                .map(PathwayMapper::toResponse)
                .collect(Collectors.toList());
    }

    // ====================================================================
    // CREATE PATHWAY (Admin)
    // ====================================================================

    @Transactional
    public ClinicalPathwayResponse createPathway(CreatePathwayRequest request) {
        if (pathwayRepository.existsByPathwayCode(request.getPathwayCode())) {
            throw new ClinicalBusinessException(
                    "Pathway with code '" + request.getPathwayCode() + "' already exists.");
        }

        ClinicalPathway pathway = ClinicalPathway.builder()
                .pathwayCode(request.getPathwayCode())
                .pathwayName(request.getPathwayName())
                .category(request.getCategory())
                .description(request.getDescription())
                .targetPopulation(request.getTargetPopulation())
                .protocolVersion(request.getProtocolVersion())
                .sourceGuideline(request.getSourceGuideline())
                .build();

        pathway = pathwayRepository.save(pathway);

        // Create steps if provided
        if (request.getSteps() != null) {
            for (CreatePathwayRequest.CreatePathwayStepRequest stepReq : request.getSteps()) {
                PathwayStep step = PathwayStep.builder()
                        .pathway(pathway)
                        .stepOrder(stepReq.getStepOrder())
                        .stepTitle(stepReq.getStepTitle())
                        .stepDescription(stepReq.getStepDescription())
                        .timeframeMinutes(stepReq.getTimeframeMinutes())
                        .isMandatory(stepReq.isMandatory())
                        .category(stepReq.getCategory())
                        .build();
                stepRepository.save(step);
            }
        }

        log.info("Clinical pathway created — code:{} name:'{}'",
                pathway.getPathwayCode(), pathway.getPathwayName());

        return PathwayMapper.toResponse(pathway);
    }

    // ====================================================================
    // INTERNAL HELPERS
    // ====================================================================

    private void generateComplianceAlert(PathwayActivation activation, List<String> overdueSteps) {
        // Only generate if no existing unacknowledged alert for this
        if (!alertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                activation.getVisit().getId(), AlertType.REASSESSMENT_DUE)) {

            String patientName = activation.getVisit().getPatient().getFirstName() + " "
                    + activation.getVisit().getPatient().getLastName();

            ClinicalAlert alert = ClinicalAlert.builder()
                    .visit(activation.getVisit())
                    .alertType(AlertType.REASSESSMENT_DUE)
                    .severity(AlertSeverity.HIGH)
                    .title("PATHWAY STEPS OVERDUE: " + activation.getPathway().getPathwayCode())
                    .message(String.format(
                            "Pathway '%s' for patient %s (Visit: %s) has %d overdue mandatory steps: %s. "
                            + "These steps have exceeded 2x their target timeframe.",
                            activation.getPathway().getPathwayName(),
                            patientName,
                            activation.getVisit().getVisitNumber(),
                            overdueSteps.size(),
                            String.join(", ", overdueSteps)))
                    .autoGenerated(true)
                    .build();

            alertRepository.save(alert);

            log.warn("Pathway compliance alert generated — visit:{} pathway:{} overdue:{}",
                    activation.getVisit().getVisitNumber(),
                    activation.getPathway().getPathwayCode(),
                    overdueSteps);
        }
    }

    private void validateActivationActive(PathwayActivation activation) {
        if (activation.getStatus() != PathwayActivationStatus.ACTIVE) {
            throw new ClinicalBusinessException(
                    "Pathway activation is not active. Current status: " + activation.getStatus());
        }
    }

    public ClinicalPathway findPathwayOrThrow(UUID id) {
        return pathwayRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalPathway", "id", id));
    }

    public PathwayActivation findActivationOrThrow(UUID id) {
        return activationRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("PathwayActivation", "id", id));
    }

    public PathwayStep findStepOrThrow(UUID id) {
        return stepRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("PathwayStep", "id", id));
    }
}
