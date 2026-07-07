package com.smartTriage.smartTriage_server.module.safety.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;
import com.smartTriage.smartTriage_server.module.safety.repository.SafetyIncidentRepository;
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
import java.util.List;
import java.util.UUID;

/**
 * SafetyIncidentFollowupMonitorService — the governance clocks the incident
 * register previously lacked. Two conditions are chased on a schedule:
 *
 * <ul>
 *   <li><b>Corrective action overdue</b>: an incident sits in
 *       CORRECTIVE_ACTION_PLANNED past its own {@code correctiveActionDeadline}
 *       without the action being implemented. A deadline nobody chases is
 *       documentation theatre.</li>
 *   <li><b>Severe incident unattended</b>: a SEVERE_HARM / DEATH report has sat
 *       in REPORTED past the review window with no investigation started — the
 *       worst-severity reports are exactly the ones that must never rot in a
 *       queue.</li>
 * </ul>
 *
 * Delivery mirrors the incident-alert design: visit-linked incidents get a
 * persisted, deduped SAFETY_FOLLOWUP_OVERDUE alert (visible + acknowledgeable in
 * the Alert Center); visit-less incidents get a transient hospital-board + charge-
 * nurse push each cycle they remain unattended (the register row is the durable
 * record, and acting on it stops the paging).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SafetyIncidentFollowupMonitorService {

    /** How long a SEVERE_HARM/DEATH report may sit in REPORTED before escalation. */
    static final Duration SEVERE_REVIEW_WINDOW = Duration.ofHours(24);

    private final SafetyIncidentRepository incidentRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    @Scheduled(fixedDelayString = "${smarttriage.safety.followup-monitor-interval-ms:600000}")
    @Transactional
    public int checkFollowups() {
        Instant now = Instant.now();
        int raised = 0;

        for (SafetyIncident i : incidentRepository.findOverdueCorrectiveActions(now)) {
            long daysOverdue = Duration.between(i.getCorrectiveActionDeadline(), now).toDays();
            raised += raiseFollowup(i,
                    "CORRECTIVE ACTION OVERDUE — " + i.getIncidentNumber(),
                    String.format(
                            "Corrective action for safety incident %s (%s, %s) passed its deadline %d day(s) ago "
                            + "and is not implemented. Owner: %s. Action: %s",
                            i.getIncidentNumber(), i.getSeverity(), i.getIncidentType(),
                            Math.max(0, daysOverdue),
                            i.getCorrectiveActionOwner() != null ? i.getCorrectiveActionOwner() : "unassigned",
                            truncate(i.getCorrectiveAction(), 120))) ? 1 : 0;
        }

        Instant cutoff = now.minus(SEVERE_REVIEW_WINDOW);
        for (SafetyIncident i : incidentRepository.findUnattendedSevereIncidents(cutoff)) {
            long hours = Duration.between(i.getReportedAt(), now).toHours();
            raised += raiseFollowup(i,
                    "SEVERE INCIDENT UNATTENDED — " + i.getIncidentNumber(),
                    String.format(
                            "Safety incident %s (%s, %s) was reported %d hour(s) ago and NO investigation has "
                            + "been started. Severe-harm and death reports require review within %d hours. %s",
                            i.getIncidentNumber(), i.getSeverity(), i.getIncidentType(), hours,
                            SEVERE_REVIEW_WINDOW.toHours(), truncate(i.getDescription(), 120))) ? 1 : 0;
        }

        if (raised > 0) log.info("Safety follow-up monitor: raised {} escalation(s)", raised);
        return raised;
    }

    /** Returns true when a new page actually went out (persisted alert or transient push). */
    private boolean raiseFollowup(SafetyIncident incident, String title, String message) {
        UUID hospitalId = incident.getHospital() != null ? incident.getHospital().getId() : null;
        if (hospitalId == null) return false;
        Visit visit = incident.getVisit();

        if (visit == null) {
            publishTransient(incident, hospitalId, title, message);
            return true;
        }

        // Dedup: one unacknowledged follow-up alert per visit at a time — acknowledging
        // it without fixing the follow-up lets the next cycle re-raise (the standard
        // ack-without-action re-page semantics).
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.SAFETY_FOLLOWUP_OVERDUE)) {
            return false;
        }

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.SAFETY_FOLLOWUP_OVERDUE)
                .severity(AlertSeverity.HIGH)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .escalationTier(2)
                .build();
        alert = clinicalAlertRepository.save(alert);
        log.warn("SAFETY FOLLOW-UP OVERDUE: {} | {}", incident.getIncidentNumber(), title);

        final ClinicalAlertResponse resp = ClinicalAlertMapper.toResponse(alert);
        final List<UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                for (UUID cnId : chargeNurseIds) realTimeEventPublisher.publishUserAlert(cnId, resp);
            } catch (Exception e) {
                log.warn("Failed to publish safety follow-up for {}: {}", incident.getIncidentNumber(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { fire.run(); }
            });
        } else {
            fire.run();
        }
        return true;
    }

    /** Visit-less incident — transient page (register row is the durable record). */
    private void publishTransient(SafetyIncident incident, UUID hospitalId, String title, String message) {
        ClinicalAlertResponse resp = ClinicalAlertResponse.builder()
                .id(incident.getId())
                .alertType(AlertType.SAFETY_FOLLOWUP_OVERDUE)
                .category(AlertType.SAFETY_FOLLOWUP_OVERDUE.getCategory())
                .severity(AlertSeverity.HIGH)
                .title(title)
                .message(message + " (No patient visit linked — review it in the Safety Incidents register.)")
                .acknowledged(false)
                .autoGenerated(true)
                .createdAt(Instant.now())
                .build();
        final List<UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                for (UUID cnId : chargeNurseIds) realTimeEventPublisher.publishUserAlert(cnId, resp);
            } catch (Exception e) {
                log.warn("Failed to publish transient safety follow-up for {}: {}",
                        incident.getIncidentNumber(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { fire.run(); }
            });
        } else {
            fire.run();
        }
        log.warn("SAFETY FOLLOW-UP OVERDUE (visit-less, transient page): {}", incident.getIncidentNumber());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "—";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
