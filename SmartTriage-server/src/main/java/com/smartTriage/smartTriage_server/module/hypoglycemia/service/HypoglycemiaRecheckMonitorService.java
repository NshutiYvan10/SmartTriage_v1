package com.smartTriage.smartTriage_server.module.hypoglycemia.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.entity.HypoglycemiaEvent;
import com.smartTriage.smartTriage_server.module.hypoglycemia.repository.HypoglycemiaEventRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HypoglycemiaRecheckMonitorService — enforces the mandatory 15-minute glucose
 * recheck. The hypoglycemia protocol's core safety promise is "recheck or
 * escalate"; the original module had no timer, so a treated-but-never-rechecked
 * (or untreated) patient produced nothing. This scheduled monitor scans
 * unresolved events whose recheckDueAt has lapsed and raises a CRITICAL,
 * owned HYPOGLYCEMIA_RECHECK_OVERDUE escalation (distinct type, deduped).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HypoglycemiaRecheckMonitorService {

    private static final java.util.Set<VisitStatus> TERMINAL_VISIT = java.util.EnumSet.of(
            VisitStatus.DISCHARGED, VisitStatus.ADMITTED, VisitStatus.ICU_ADMITTED,
            VisitStatus.TRANSFERRED, VisitStatus.LEFT_WITHOUT_BEING_SEEN, VisitStatus.DECEASED);

    private final HypoglycemiaEventRepository hypoglycemiaEventRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    @Scheduled(fixedDelayString = "${smarttriage.hypoglycemia.recheck-monitor-interval-ms:60000}")
    @Transactional
    public int checkRecheckOverdue() {
        List<HypoglycemiaEvent> open = hypoglycemiaEventRepository.findByResolvedFalseAndIsActiveTrue();
        Instant now = Instant.now();
        int raised = 0;

        for (HypoglycemiaEvent event : open) {
            if (event.getRecheckDueAt() == null || !event.getRecheckDueAt().isBefore(now)) continue;
            Visit visit = event.getVisit();
            if (visit == null) continue;
            if (visit.getStatus() != null && TERMINAL_VISIT.contains(visit.getStatus())) continue;

            UUID visitId = visit.getId();
            if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                    visitId, AlertType.HYPOGLYCEMIA_RECHECK_OVERDUE)) {
                continue;
            }
            raiseRecheckOverdue(event, visit, Duration.between(event.getRecheckDueAt(), now).toMinutes());
            raised++;
            log.error("HYPOGLYCEMIA RECHECK OVERDUE: visit {} | recheck due {} min ago",
                    visit.getVisitNumber(), Duration.between(event.getRecheckDueAt(), now).toMinutes());
        }
        if (raised > 0) log.info("Hypoglycemia recheck monitor: raised {} overdue escalation(s)", raised);
        return raised;
    }

    private void raiseRecheckOverdue(HypoglycemiaEvent event, Visit visit, long minutesOverdue) {
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
                .alertType(AlertType.HYPOGLYCEMIA_RECHECK_OVERDUE)
                .severity(AlertSeverity.CRITICAL)
                .title("HYPOGLYCEMIA RECHECK OVERDUE")
                .message(String.format(
                        "The mandatory 15-minute glucose recheck for %s (Visit: %s) is %d minute(s) overdue on an "
                        + "UNRESOLVED hypoglycemia event. Recheck glucose now and treat/escalate.",
                        patientName, visit.getVisitNumber(), Math.max(0, minutesOverdue)))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(2)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);

        try {
            if (hospitalId != null) {
                var resp = ClinicalAlertMapper.toResponse(alert);
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (zone != null) realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
                if (zoneDoctor != null) realTimeEventPublisher.publishUserAlert(zoneDoctor.getId(), resp);
                for (User cn : shiftAssignmentService.getChargeNurse(hospitalId)) {
                    realTimeEventPublisher.publishUserAlert(cn.getId(), resp);
                }
                realTimeEventPublisher.publishHypoglycemiaEventAfterCommit(hospitalId, Map.of(
                        "eventType", "RECHECK_OVERDUE",
                        "visitId", visit.getId().toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to publish hypoglycemia recheck-overdue for visit {}: {}", visit.getId(), e.getMessage());
        }
    }
}
