package com.smartTriage.smartTriage_server.module.hypoglycemia.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hypoglycemia.service.GlucoseScheduleService.DueEntry;
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
import java.util.UUID;

/**
 * GlucoseScheduleMonitorService — the proactive half of glucose safety. The
 * detection pipeline can only react to readings that arrive; this monitor
 * chases the readings that DON'T. Every scan it recomputes the due-clock for
 * all in-department patients ({@link GlucoseScheduleService}) and:
 * <ul>
 *   <li><b>DUE</b> — reading due, grace not yet elapsed → MEDIUM
 *       GLUCOSE_MEASUREMENT_DUE to the patient's zone (the nurses' task).
 *       One reminder per due-cycle: deduped against any same-type alert filed
 *       after this cycle's dueAt, so an acknowledged reminder doesn't re-page —
 *       the OVERDUE escalation is the enforcement.</li>
 *   <li><b>OVERDUE</b> — grace elapsed, still no reading → HIGH
 *       GLUCOSE_MEASUREMENT_OVERDUE, owned (zone doctor + charge nurse),
 *       deduped ONLY against an open unacknowledged instance: acknowledging
 *       without recording a glucose re-pages next scan ("ack is not action" —
 *       same contract as the 15-minute recheck monitor).</li>
 * </ul>
 * Recording a glucose through ANY door (vitals, triage, lab, event recheck)
 * advances dueAt and silences both paths with no manual clock management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlucoseScheduleMonitorService {

    private final GlucoseScheduleService glucoseScheduleService;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    @Scheduled(fixedDelayString = "${smarttriage.glucose.schedule.monitor-interval-ms:120000}",
               initialDelayString = "${smarttriage.glucose.schedule.monitor-initial-delay-ms:30000}")
    @Transactional
    public int scan() {
        Instant now = Instant.now();
        int raised = 0;
        for (DueEntry entry : glucoseScheduleService.computeAll(now)) {
            try {
                if (entry.overdue(now)) {
                    if (raiseOverdue(entry, now)) raised++;
                } else if (entry.due(now)) {
                    if (raiseDue(entry, now)) raised++;
                }
            } catch (Exception e) {
                log.warn("Glucose due-clock alert failed for visit {}: {}",
                        entry.visit().getId(), e.getMessage());
            }
        }
        if (raised > 0) log.info("Glucose schedule monitor: raised {} due/overdue alert(s)", raised);
        return raised;
    }

    private boolean raiseDue(DueEntry entry, Instant now) {
        UUID visitId = entry.visit().getId();
        // One reminder per due-cycle: any DUE alert filed after this cycle's dueAt
        // covers it (acknowledged or not). A new reading advances dueAt past the old
        // alert's createdAt, which is what re-arms the reminder for the next cycle.
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrueAndCreatedAtAfter(
                visitId, AlertType.GLUCOSE_MEASUREMENT_DUE, entry.dueAt())) {
            return false;
        }
        // An open OVERDUE escalation supersedes a reminder.
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.GLUCOSE_MEASUREMENT_OVERDUE)) {
            return false;
        }

        Visit visit = entry.visit();
        String message = String.format(
                "Bedside glucose is due for %s (Visit: %s) — %s tier, every %s. %s Record a glucose now "
                + "(vitals, POC or lab); it clears this reminder automatically.",
                patientName(visit), visit.getVisitNumber(), entry.tier().label(),
                humanInterval(entry.tier().interval()), lastReadingPhrase(entry, now));
        raise(entry, AlertType.GLUCOSE_MEASUREMENT_DUE, AlertSeverity.MEDIUM,
                "GLUCOSE MEASUREMENT DUE", message, 1, false);
        log.info("Glucose measurement DUE: visit {} | tier {} | due {}",
                visit.getVisitNumber(), entry.tier().key(), entry.dueAt());
        return true;
    }

    private boolean raiseOverdue(DueEntry entry, Instant now) {
        UUID visitId = entry.visit().getId();
        // Dedup on UNACKED only — acknowledging without recording a reading re-pages.
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visitId, AlertType.GLUCOSE_MEASUREMENT_OVERDUE)) {
            return false;
        }

        Visit visit = entry.visit();
        long minutesOverdue = Duration.between(entry.dueAt(), now).toMinutes();
        String message = String.format(
                "Scheduled bedside glucose for %s (Visit: %s) is %d minute(s) overdue (%s tier, every %s). "
                + "%s Measure and record a glucose NOW — this escalation re-pages until a reading is recorded.",
                patientName(visit), visit.getVisitNumber(), Math.max(0, minutesOverdue),
                entry.tier().label(), humanInterval(entry.tier().interval()), lastReadingPhrase(entry, now));
        raise(entry, AlertType.GLUCOSE_MEASUREMENT_OVERDUE, AlertSeverity.HIGH,
                "GLUCOSE MEASUREMENT OVERDUE", message, 2, true);
        log.warn("Glucose measurement OVERDUE: visit {} | tier {} | {} min past due",
                visit.getVisitNumber(), entry.tier().key(), minutesOverdue);
        return true;
    }

    private void raise(DueEntry entry, AlertType type, AlertSeverity severity,
                       String title, String message, int tier, boolean ownedByDoctor) {
        Visit visit = entry.visit();
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = null;
        if (ownedByDoctor && hospitalId != null && zone != null) {
            List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
            if (!doctors.isEmpty()) zoneDoctor = doctors.get(0);
        }

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(type)
                .severity(severity)
                .title(title)
                .message(message)
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(tier)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);

        // Fan out AFTER COMMIT — same contract as the recheck monitor: never push a
        // page whose backing alert row could still roll back. Response built in-tx.
        if (hospitalId == null) return;
        final var resp = ClinicalAlertMapper.toResponse(alert);
        final UUID hid = hospitalId;
        final EdZone z = zone;
        final UUID doctorId = zoneDoctor != null ? zoneDoctor.getId() : null;
        final List<UUID> chargeNurseIds = ownedByDoctor
                ? shiftAssignmentService.getChargeNurse(hospitalId).stream().map(User::getId).toList()
                : List.of();
        final UUID visitId = visit.getId();
        final UUID alertId = alert.getId();
        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hid, resp);
                if (z != null) realTimeEventPublisher.publishZoneAlert(hid, z, resp);
                if (doctorId != null) realTimeEventPublisher.publishUserAlert(doctorId, resp);
                for (UUID cnId : chargeNurseIds) {
                    realTimeEventPublisher.publishUserAlert(cnId, resp);
                }
                realTimeEventPublisher.publishHypoglycemiaEvent(hid, java.util.Map.of(
                        "eventType", type.name(),
                        "visitId", visitId.toString()));
            } catch (Exception e) {
                log.warn("Failed to publish glucose schedule alert {}: {}", alertId, e.getMessage());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { fire.run(); }
                    });
        } else {
            fire.run();
        }
    }

    private static String lastReadingPhrase(DueEntry entry, Instant now) {
        if (entry.lastReadingAt() == null) return "No glucose has been recorded this visit.";
        long h = Duration.between(entry.lastReadingAt(), now).toHours();
        long m = Duration.between(entry.lastReadingAt(), now).toMinutes() % 60;
        return String.format("Last reading %s ago.", h > 0 ? h + " h " + m + " min" : m + " min");
    }

    private static String humanInterval(Duration interval) {
        long minutes = interval.toMinutes();
        if (minutes % 60 == 0) return (minutes / 60) + " h";
        return minutes + " min";
    }

    private static String patientName(Visit visit) {
        if (visit.getPatient() == null) return "patient";
        return visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
    }
}
