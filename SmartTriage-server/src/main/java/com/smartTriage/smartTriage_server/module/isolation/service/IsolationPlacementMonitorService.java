package com.smartTriage.smartTriage_server.module.isolation.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.isolation.entity.InfectionScreening;
import com.smartTriage.smartTriage_server.module.isolation.repository.InfectionScreeningRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IsolationPlacementMonitorService — enforces timely physical isolation. A flagged
 * patient who is not moved into an isolation room within the placement window keeps
 * exposing other patients and staff, so this scheduled monitor scans isolations that
 * are required-but-not-roomed and, once {@code placementDueAt} lapses, raises a
 * CRITICAL, owned ISOLATION_PLACEMENT_OVERDUE escalation (distinct type, deduped,
 * terminal-visit-skipping) — mirroring the sepsis/hypoglycemia monitors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IsolationPlacementMonitorService {

    private static final java.util.Set<VisitStatus> TERMINAL_VISIT = java.util.EnumSet.of(
            VisitStatus.DISCHARGED, VisitStatus.ADMITTED, VisitStatus.ICU_ADMITTED,
            VisitStatus.TRANSFERRED, VisitStatus.LEFT_WITHOUT_BEING_SEEN, VisitStatus.DECEASED);

    private final InfectionScreeningRepository screeningRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    @Scheduled(fixedDelayString = "${smarttriage.isolation.placement-monitor-interval-ms:60000}")
    @Transactional
    public int checkPlacementOverdue() {
        List<InfectionScreening> awaiting = screeningRepository.findAwaitingPlacement();
        Instant now = Instant.now();
        int raised = 0;

        for (InfectionScreening s : awaiting) {
            if (s.getPlacementDueAt() == null || !s.getPlacementDueAt().isBefore(now)) continue;
            Visit visit = s.getVisit();
            if (visit == null) continue;
            if (visit.getStatus() != null && TERMINAL_VISIT.contains(visit.getStatus())) continue;

            UUID visitId = visit.getId();
            if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                    visitId, AlertType.ISOLATION_PLACEMENT_OVERDUE)) {
                continue;
            }
            long minutesOverdue = Duration.between(s.getPlacementDueAt(), now).toMinutes();
            raisePlacementOverdue(s, visit, minutesOverdue);
            raised++;
            log.error("ISOLATION PLACEMENT OVERDUE: visit {} | {} isolation due {} min ago",
                    visit.getVisitNumber(), s.getIsolationType(), minutesOverdue);
        }
        if (raised > 0) log.info("Isolation placement monitor: raised {} overdue escalation(s)", raised);
        return raised;
    }

    private void raisePlacementOverdue(InfectionScreening s, Visit visit, long minutesOverdue) {
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
                .alertType(AlertType.ISOLATION_PLACEMENT_OVERDUE)
                .severity(AlertSeverity.CRITICAL)
                .title("ISOLATION PLACEMENT OVERDUE")
                .message(String.format(
                        "%s (Visit: %s) requires %s isolation but has NOT been moved to an isolation room — "
                        + "%d minute(s) overdue. Place the patient now to stop ongoing exposure; assign a room "
                        + "or escalate to the charge nurse for bed reassignment.",
                        patientName, visit.getVisitNumber(), s.getIsolationType(), Math.max(0, minutesOverdue)))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(2)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);

        if (hospitalId == null) return;
        // Defer the alert broadcast until AFTER COMMIT so a rolled-back monitor tx
        // cannot leave a phantom placement-overdue alert on subscribers' screens
        // (mirrors InfectionIsolationService.publishOwnedAlert).
        final var resp = ClinicalAlertMapper.toResponse(alert);
        final java.util.UUID doctorId = zoneDoctor != null ? zoneDoctor.getId() : null;
        final List<java.util.UUID> chargeNurseIds = shiftAssignmentService.getChargeNurse(hospitalId)
                .stream().map(User::getId).toList();
        final java.util.UUID visitId = visit.getId();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (zone != null) realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
                if (doctorId != null) realTimeEventPublisher.publishUserAlert(doctorId, resp);
                for (java.util.UUID cnId : chargeNurseIds) {
                    realTimeEventPublisher.publishUserAlert(cnId, resp);
                }
                realTimeEventPublisher.publishIsolationEvent(hospitalId, Map.of(
                        "eventType", "PLACEMENT_OVERDUE", "visitId", visitId.toString()));
            } catch (Exception e) {
                log.warn("Failed to publish isolation placement-overdue for visit {}: {}", visitId, e.getMessage());
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
