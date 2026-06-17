package com.smartTriage.smartTriage_server.module.pathway.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.PathwayActivationStatus;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayActivation;
import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayStep;
import com.smartTriage.smartTriage_server.module.pathway.entity.PathwayStepCompletion;
import com.smartTriage.smartTriage_server.module.pathway.repository.PathwayActivationRepository;
import com.smartTriage.smartTriage_server.module.pathway.repository.PathwayStepCompletionRepository;
import com.smartTriage.smartTriage_server.module.pathway.repository.PathwayStepRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PathwayComplianceMonitorService — enforces protocol timing. A mandatory pathway
 * step that passes its own clinical timeframe without being done/skipped keeps the
 * patient off-protocol, so this scheduled monitor scans ACTIVE activations and
 * raises a CRITICAL, OWNED PATHWAY_STEP_OVERDUE escalation (distinct type, deduped,
 * terminal-visit-skipping, after-commit push). Replaces the old lazy/save-only/
 * REASSESSMENT_DUE compliance alert that only fired when someone happened to open
 * the progress view. Escalation is at 1x the step timeframe (its actual deadline),
 * not the old 2x.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PathwayComplianceMonitorService {

    private static final Set<VisitStatus> TERMINAL_VISIT = java.util.EnumSet.of(
            VisitStatus.DISCHARGED, VisitStatus.ADMITTED, VisitStatus.ICU_ADMITTED,
            VisitStatus.TRANSFERRED, VisitStatus.LEFT_WITHOUT_BEING_SEEN, VisitStatus.DECEASED);

    private final PathwayActivationRepository activationRepository;
    private final PathwayStepRepository stepRepository;
    private final PathwayStepCompletionRepository completionRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    @Scheduled(fixedDelayString = "${smarttriage.pathway.compliance-monitor-interval-ms:60000}")
    @Transactional
    public int checkOverdueSteps() {
        List<PathwayActivation> active = activationRepository.findByStatusAndIsActiveTrue(PathwayActivationStatus.ACTIVE);
        Instant now = Instant.now();
        int raised = 0;

        for (PathwayActivation activation : active) {
            Visit visit = activation.getVisit();
            if (visit == null) continue;
            if (visit.getStatus() != null && TERMINAL_VISIT.contains(visit.getStatus())) continue;

            List<String> overdue = overdueMandatorySteps(activation, now);
            if (overdue.isEmpty()) continue;

            UUID visitId = visit.getId();
            if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                    visitId, AlertType.PATHWAY_STEP_OVERDUE)) {
                continue;
            }
            raiseOverdue(activation, visit, overdue);
            raised++;
            log.error("PATHWAY STEP OVERDUE: visit {} | pathway {} | {} overdue mandatory step(s)",
                    visit.getVisitNumber(), activation.getPathway().getPathwayCode(), overdue.size());
        }
        if (raised > 0) log.info("Pathway compliance monitor: raised {} overdue escalation(s)", raised);
        return raised;
    }

    /** Mandatory steps past their own timeframe (1x) from activation, not yet done/skipped. */
    private List<String> overdueMandatorySteps(PathwayActivation activation, Instant now) {
        long minutesSince = Duration.between(activation.getActivatedAt(), now).toMinutes();
        Set<UUID> doneStepIds = completionRepository
                .findByActivationIdAndIsActiveTrueOrderByCompletedAtAsc(activation.getId())
                .stream().map(c -> c.getStep().getId()).collect(Collectors.toSet());
        List<String> overdue = new ArrayList<>();
        for (PathwayStep step : stepRepository
                .findByPathwayIdAndIsActiveTrueOrderByStepOrderAsc(activation.getPathway().getId())) {
            if (step.isMandatory() && step.getTimeframeMinutes() != null
                    && minutesSince > step.getTimeframeMinutes()
                    && !doneStepIds.contains(step.getId())) {
                overdue.add(step.getStepTitle());
            }
        }
        return overdue;
    }

    private void raiseOverdue(PathwayActivation activation, Visit visit, List<String> overdue) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = null;
        if (hospitalId != null && zone != null) {
            List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
            if (!doctors.isEmpty()) zoneDoctor = doctors.get(0);
        }
        String patientName = visit.getPatient() != null
                ? (visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName())
                : "patient";

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.PATHWAY_STEP_OVERDUE)
                .severity(AlertSeverity.CRITICAL)
                .title("PATHWAY STEPS OVERDUE: " + activation.getPathway().getPathwayCode())
                .message(String.format(
                        "Pathway '%s' for %s (Visit: %s) has %d mandatory step(s) past their protocol timeframe: %s. "
                        + "Complete or skip-with-reason each now, or escalate.",
                        activation.getPathway().getPathwayName(), patientName, visit.getVisitNumber(),
                        overdue.size(), String.join(", ", overdue)))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(2)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);

        if (hospitalId == null) return;
        final var resp = ClinicalAlertMapper.toResponse(alert);
        final UUID doctorId = zoneDoctor != null ? zoneDoctor.getId() : null;
        final List<UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();
        final UUID visitId = visit.getId();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (zone != null) realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
                if (doctorId != null) realTimeEventPublisher.publishUserAlert(doctorId, resp);
                for (UUID cnId : chargeNurseIds) {
                    realTimeEventPublisher.publishUserAlert(cnId, resp);
                }
                realTimeEventPublisher.publishPathwayEvent(hospitalId, Map.of(
                        "eventType", "STEP_OVERDUE", "visitId", visitId.toString()));
            } catch (Exception e) {
                log.warn("Failed to publish pathway overdue for visit {}: {}", visitId, e.getMessage());
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
}
