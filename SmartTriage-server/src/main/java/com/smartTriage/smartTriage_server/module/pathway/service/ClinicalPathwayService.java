package com.smartTriage.smartTriage_server.module.pathway.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.PathwayActivationStatus;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.pathway.dto.*;
import com.smartTriage.smartTriage_server.module.pathway.engine.PathwayRecommendationEngine;
import com.smartTriage.smartTriage_server.module.pathway.entity.*;
import com.smartTriage.smartTriage_server.module.pathway.mapper.PathwayMapper;
import com.smartTriage.smartTriage_server.module.pathway.repository.*;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final ShiftAssignmentService shiftAssignmentService;

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

        // Actor is the authenticated user; fall back to the (optional) request name.
        String actor = resolveCurrentUserName();
        PathwayActivation activation = PathwayActivation.builder()
                .visit(visit)
                .pathway(pathway)
                .activatedAt(Instant.now())
                .activatedByName(actor != null ? actor : activatedByName)
                .status(PathwayActivationStatus.ACTIVE)
                .notes(notes)
                .build();

        try {
            // saveAndFlush so a concurrent-activation race trips the partial unique
            // index HERE (catchable) rather than at commit; convert to a clean 409.
            activation = activationRepository.saveAndFlush(activation);
        } catch (DataIntegrityViolationException e) {
            throw new ClinicalBusinessException(
                    "Pathway '" + pathway.getPathwayName() + "' is already active for this visit.");
        }

        generatePathwayActivatedAlert(visit, pathway);
        publishPathwayDashboard(visit, "ACTIVATED");
        log.info("Pathway activated — visit:{} pathway:{} ({}) by:{}",
                visit.getVisitNumber(), pathway.getPathwayCode(),
                pathway.getPathwayName(), activation.getActivatedByName());

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
        validateStepBelongsToPathway(step, activation);

        // Check if already completed
        if (completionRepository.existsByActivationIdAndStepIdAndIsActiveTrue(activationId, stepId)) {
            throw new ClinicalBusinessException("Step '" + step.getStepTitle() + "' has already been completed.");
        }

        String actor = resolveCurrentUserName();
        int minutesSinceActivation = (int) Duration.between(activation.getActivatedAt(), Instant.now()).toMinutes();

        PathwayStepCompletion completion = PathwayStepCompletion.builder()
                .activation(activation)
                .step(step)
                .completedAt(Instant.now())
                .completedByName(actor != null ? actor : request.getCompletedByName())
                .wasSkipped(false)
                .notes(request.getNotes())
                .timeToCompleteMinutes(minutesSinceActivation)
                .build();

        completion = saveCompletionGuarded(completion, step);
        publishPathwayDashboard(activation.getVisit(), "STEP_COMPLETED");

        log.info("Pathway step completed — activation:{} step:{} '{}' by:{} ({}min)",
                activationId, stepId, step.getStepTitle(),
                completion.getCompletedByName(), minutesSinceActivation);

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
        validateStepBelongsToPathway(step, activation);

        if (completionRepository.existsByActivationIdAndStepIdAndIsActiveTrue(activationId, stepId)) {
            throw new ClinicalBusinessException("Step '" + step.getStepTitle() + "' has already been completed or skipped.");
        }

        String actor = resolveCurrentUserName();
        int minutesSinceActivation = (int) Duration.between(activation.getActivatedAt(), Instant.now()).toMinutes();

        PathwayStepCompletion completion = PathwayStepCompletion.builder()
                .activation(activation)
                .step(step)
                .completedAt(Instant.now())
                .completedByName(actor != null ? actor : completedByName)
                .wasSkipped(true)
                .skipReason(reason)
                .timeToCompleteMinutes(minutesSinceActivation)
                .build();

        completion = saveCompletionGuarded(completion, step);
        publishPathwayDashboard(activation.getVisit(), "STEP_SKIPPED");

        log.info("Pathway step skipped — activation:{} step:{} '{}' reason:'{}' by:{}",
                activationId, stepId, step.getStepTitle(), reason, completion.getCompletedByName());

        return PathwayMapper.toResponse(completion);
    }

    // ====================================================================
    // COMPLETE PATHWAY
    // ====================================================================

    @Transactional
    public PathwayActivationResponse completePathway(UUID activationId) {
        PathwayActivation activation = findActivationOrThrow(activationId);
        validateActivationActive(activation);

        // Do not let a pathway be marked complete while mandatory steps are still
        // outstanding — that would silently sign off an unfinished protocol. The
        // clinician must complete or explicitly skip-with-reason each mandatory step,
        // or Abandon the pathway (which records a deviation reason).
        List<String> outstanding = outstandingMandatorySteps(activation);
        if (!outstanding.isEmpty()) {
            throw new ClinicalBusinessException(
                    "Cannot complete pathway — " + outstanding.size() + " mandatory step(s) are still outstanding: "
                    + String.join(", ", outstanding) + ". Complete or skip (with reason) each, or Abandon the pathway.");
        }

        activation.setStatus(PathwayActivationStatus.COMPLETED);
        activation.setCompletedAt(Instant.now());

        activation = activationRepository.save(activation);
        publishPathwayDashboard(activation.getVisit(), "COMPLETED");

        log.info("Pathway completed — activation:{} pathway:{}",
                activationId, activation.getPathway().getPathwayCode());

        return PathwayMapper.toResponse(activation);
    }

    /** Mandatory step titles for this activation's pathway that are neither completed nor skipped. */
    private List<String> outstandingMandatorySteps(PathwayActivation activation) {
        List<PathwayStep> steps = stepRepository
                .findByPathwayIdAndIsActiveTrueOrderByStepOrderAsc(activation.getPathway().getId());
        java.util.Set<UUID> doneStepIds = completionRepository
                .findByActivationIdAndIsActiveTrueOrderByCompletedAtAsc(activation.getId())
                .stream().map(c -> c.getStep().getId()).collect(Collectors.toSet());
        return steps.stream()
                .filter(PathwayStep::isMandatory)
                .filter(s -> !doneStepIds.contains(s.getId()))
                .map(PathwayStep::getStepTitle)
                .collect(Collectors.toList());
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
        publishPathwayDashboard(activation.getVisit(), "ABANDONED");

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
                // Overdue once a mandatory step passes its own protocol timeframe (1x) —
                // the step's actual clinical deadline, consistent with the compliance monitor.
                if (step.getTimeframeMinutes() != null && step.isMandatory()
                        && minutesSinceActivation > step.getTimeframeMinutes()) {
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
                    .skipReason(completion != null && completion.isWasSkipped() ? completion.getSkipReason() : null)
                    .build());
        }

        double completionPercentage = allSteps.isEmpty() ? 0.0
                : ((double) (completedCount + skippedCount) / allSteps.size()) * 100.0;

        // NB: overdue-step ESCALATION is owned by PathwayComplianceMonitorService
        // (scheduled, owned, deduped) — this read-only progress query only computes
        // the OVERDUE display status; it must not raise alerts as a side effect.

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

    /** On activation, page the zone doctor + charge nurse so a time-critical protocol is coordinated. */
    private void generatePathwayActivatedAlert(Visit visit, ClinicalPathway pathway) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(hospitalId, zone);

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.PATHWAY_ACTIVATED)
                .severity(AlertSeverity.HIGH)
                .title("PATHWAY ACTIVATED: " + pathway.getPathwayName())
                .message(String.format(
                        "Clinical pathway '%s' (%s) activated for %s (Visit: %s). Follow the protocol checklist; "
                        + "mandatory steps are time-targeted and will escalate if overdue.",
                        pathway.getPathwayName(), pathway.getPathwayCode(),
                        patientName(visit), visit.getVisitNumber()))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .escalationTier(1)
                .build();
        alert = alertRepository.save(alert);
        publishOwnedAlert(alert, hospitalId, zone, zoneDoctor);
        log.info("PATHWAY_ACTIVATED alert generated: visit={}, pathway={}, zone={}, doctor={}",
                visit.getId(), pathway.getPathwayCode(), zone, zoneDoctor != null ? zoneDoctor.getId() : "unassigned");
    }

    /** Push the alert to the zone board + zone doctor + charge nurse(s) AFTER COMMIT (best-effort). */
    private void publishOwnedAlert(ClinicalAlert alert, UUID hospitalId, EdZone zone, User zoneDoctor) {
        if (hospitalId == null || alert == null) return;
        final var resp = ClinicalAlertMapper.toResponse(alert);
        final UUID doctorId = zoneDoctor != null ? zoneDoctor.getId() : null;
        final List<UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();
        final UUID alertId = alert.getId();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (zone != null) realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
                if (doctorId != null) realTimeEventPublisher.publishUserAlert(doctorId, resp);
                for (UUID cnId : chargeNurseIds) {
                    realTimeEventPublisher.publishUserAlert(cnId, resp);
                }
            } catch (Exception e) {
                log.warn("Failed to publish pathway alert {}: {}", alertId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { fire.run(); }
            });
        } else {
            fire.run();
        }
    }

    private void publishPathwayDashboard(Visit visit, String eventType) {
        try {
            if (visit == null) return;
            UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
            if (hospitalId != null) {
                realTimeEventPublisher.publishPathwayEventAfterCommit(hospitalId, Map.of(
                        "eventType", eventType, "visitId", visit.getId().toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to publish pathway dashboard event: {}", e.getMessage());
        }
    }

    private User resolveZoneDoctor(UUID hospitalId, EdZone zone) {
        if (hospitalId == null || zone == null) return null;
        List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
        return doctors.isEmpty() ? null : doctors.get(0);
    }

    private String patientName(Visit visit) {
        if (visit.getPatient() == null) return "patient";
        return visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
    }

    private String resolveCurrentUserName() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) {
                return user.getFirstName() + " " + user.getLastName();
            }
        } catch (Exception ignored) {
            // no resolvable principal (scheduled / system context)
        }
        return null;
    }

    /** saveAndFlush so a concurrent complete/skip of the same step trips the V80 unique
     *  index HERE (catchable) rather than at commit; convert to the same clean 409. */
    private PathwayStepCompletion saveCompletionGuarded(PathwayStepCompletion completion, PathwayStep step) {
        try {
            return completionRepository.saveAndFlush(completion);
        } catch (DataIntegrityViolationException e) {
            throw new ClinicalBusinessException("Step '" + step.getStepTitle() + "' has already been completed or skipped.");
        }
    }

    private void validateStepBelongsToPathway(PathwayStep step, PathwayActivation activation) {
        if (step.getPathway() == null || activation.getPathway() == null
                || !step.getPathway().getId().equals(activation.getPathway().getId())) {
            throw new ClinicalBusinessException("Step does not belong to the activated pathway.");
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
