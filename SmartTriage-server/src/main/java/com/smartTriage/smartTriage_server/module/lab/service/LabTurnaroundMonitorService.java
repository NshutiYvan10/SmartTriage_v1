package com.smartTriage.smartTriage_server.module.lab.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.mapper.LabOrderMapper;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
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
 * LabTurnaroundMonitorService — scheduled service that monitors lab order
 * turnaround times and escalates overdue orders.
 *
 * Runs every 60 seconds and checks:
 *   - STAT orders: if > 30 min from orderedAt without result → HIGH alert
 *   - URGENT orders: if > 2 hours from orderedAt without result → HIGH alert
 *   - Critical results not acknowledged within 15 minutes → CRITICAL escalation
 *   - Stuck-in-ORDERED (specimen not yet received) past ~1/3 of total
 *     SLA → MEDIUM/HIGH early-warning alert so the lab tech can act
 *     before the total turnaround target is missed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabTurnaroundMonitorService {

    private final LabOrderRepository labOrderRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final ShiftAssignmentService shiftAssignmentService;

    private static final int STAT_OVERDUE_MINUTES = 30;
    private static final int URGENT_OVERDUE_MINUTES = 120;
    private static final int ROUTINE_OVERDUE_MINUTES = 1440; // 24 h total-turnaround SLA
    private static final int CRITICAL_ACK_TIMEOUT_MINUTES = 15;

    /**
     * Stuck-in-ORDERED thresholds: ~1/3 of each priority's total
     * turnaround target. Hitting these means the lab hasn't picked
     * the specimen up yet and the full SLA will likely be missed
     * unless someone acts now.
     */
    private static final int STAT_NOT_RECEIVED_MINUTES = 10;     // STAT target 30  → alert at 10
    private static final int URGENT_NOT_RECEIVED_MINUTES = 30;   // URGENT target 120 → alert at 30
    private static final int ROUTINE_NOT_RECEIVED_MINUTES = 240; // ROUTINE target 1440 → alert at 240 (4 h)

    /**
     * Monitor lab turnaround times and generate alerts for overdue orders.
     * Runs every 60 seconds.
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void monitorTurnaroundTimes() {
        Instant now = Instant.now();

        checkStuckInOrdered(now);
        checkOverdueStatOrders(now);
        checkOverdueUrgentOrders(now);
        checkOverdueRoutineOrders(now);
        checkUnacknowledgedCriticalResults(now);
    }

    /**
     * Stuck-in-ORDERED check. Catches orders whose specimen hasn't
     * been received by the lab (status still ORDERED) past a
     * priority-specific fraction of the total SLA. Fires BEFORE
     * STAT_LAB_OVERDUE / URGENT_LAB_OVERDUE so there's a chance to
     * pick the specimen up before the full turnaround target is
     * missed.
     */
    private void checkStuckInOrdered(Instant now) {
        raiseStuckIfAny(now, LabPriority.STAT,    STAT_NOT_RECEIVED_MINUTES,    AlertSeverity.HIGH);
        raiseStuckIfAny(now, LabPriority.URGENT,  URGENT_NOT_RECEIVED_MINUTES,  AlertSeverity.HIGH);
        raiseStuckIfAny(now, LabPriority.ROUTINE, ROUTINE_NOT_RECEIVED_MINUTES, AlertSeverity.MEDIUM);
    }

    private void raiseStuckIfAny(Instant now, LabPriority priority,
                                  int thresholdMinutes, AlertSeverity severity) {
        Instant cutoff = now.minus(Duration.ofMinutes(thresholdMinutes));
        List<LabOrder> stuck = labOrderRepository.findStuckInOrderedByPriority(priority, cutoff);

        for (LabOrder order : stuck) {
            long minutesWaiting = Duration.between(order.getOrderedAt(), now).toMinutes();
            // Dedupe is visit-scoped (the alert pipeline has no per-order link); the
            // message text identifies which order this is about.
            raiseLabAlert(order, AlertType.LAB_NOT_RECEIVED, severity,
                    priority.name() + " specimen not received: " + order.getTestName(),
                    String.format(
                            "%s lab order %s (%s) has been in ORDERED status for %d min " +
                                    "(threshold: %d min, total SLA: %d min). The specimen has not " +
                                    "been received by the lab yet — collect and deliver to avoid " +
                                    "missing the turnaround target. Ordered by %s.",
                            priority.name(), order.getOrderNumber(), order.getTestName(),
                            minutesWaiting, thresholdMinutes, priority.getTargetMinutes(),
                            order.getOrderedByName() != null ? order.getOrderedByName() : "unknown"));
        }
    }

    /**
     * Check STAT orders — alert if > 30 minutes without result.
     */
    private void checkOverdueStatOrders(Instant now) {
        Instant statCutoff = now.minus(Duration.ofMinutes(STAT_OVERDUE_MINUTES));
        List<LabOrder> overdueStatOrders = labOrderRepository.findOverdueOrdersByPriority(
                LabPriority.STAT, statCutoff);

        for (LabOrder order : overdueStatOrders) {
            long minutesWaiting = Duration.between(order.getOrderedAt(), now).toMinutes();
            raiseLabAlert(order, AlertType.STAT_LAB_OVERDUE, AlertSeverity.HIGH,
                    "STAT LAB OVERDUE: " + order.getTestName(),
                    String.format("STAT lab order %s (%s) has been waiting %d minutes (target: %d min). Ordered by %s.",
                            order.getOrderNumber(), order.getTestName(), minutesWaiting, STAT_OVERDUE_MINUTES,
                            order.getOrderedByName() != null ? order.getOrderedByName() : "unknown"));
        }
    }

    /**
     * Check URGENT orders — alert if > 2 hours without result.
     */
    private void checkOverdueUrgentOrders(Instant now) {
        Instant urgentCutoff = now.minus(Duration.ofMinutes(URGENT_OVERDUE_MINUTES));
        List<LabOrder> overdueUrgentOrders = labOrderRepository.findOverdueOrdersByPriority(
                LabPriority.URGENT, urgentCutoff);

        for (LabOrder order : overdueUrgentOrders) {
            long minutesWaiting = Duration.between(order.getOrderedAt(), now).toMinutes();
            raiseLabAlert(order, AlertType.URGENT_LAB_OVERDUE, AlertSeverity.HIGH,
                    "URGENT LAB OVERDUE: " + order.getTestName(),
                    String.format("URGENT lab order %s (%s) has been waiting %d minutes (target: %d min). Ordered by %s.",
                            order.getOrderNumber(), order.getTestName(), minutesWaiting, URGENT_OVERDUE_MINUTES,
                            order.getOrderedByName() != null ? order.getOrderedByName() : "unknown"));
        }
    }

    /**
     * ROUTINE orders that have blown their total-turnaround SLA (24 h) without a result.
     * Previously a routine order got only the stuck-in-ORDERED early warning; once the
     * specimen was received it could sit indefinitely with no breach alert.
     */
    private void checkOverdueRoutineOrders(Instant now) {
        Instant routineCutoff = now.minus(Duration.ofMinutes(ROUTINE_OVERDUE_MINUTES));
        List<LabOrder> overdue = labOrderRepository.findOverdueOrdersByPriority(
                LabPriority.ROUTINE, routineCutoff);

        for (LabOrder order : overdue) {
            long minutesWaiting = Duration.between(order.getOrderedAt(), now).toMinutes();
            raiseLabAlert(order, AlertType.ROUTINE_LAB_OVERDUE, AlertSeverity.MEDIUM,
                    "Lab overdue: " + order.getTestName(),
                    String.format("Routine lab order %s (%s) has been waiting %d minutes (target: %d min). Ordered by %s.",
                            order.getOrderNumber(), order.getTestName(), minutesWaiting, ROUTINE_OVERDUE_MINUTES,
                            order.getOrderedByName() != null ? order.getOrderedByName() : "unknown"));
        }
    }

    /**
     * Check critical results not acknowledged within 15 minutes — escalate.
     */
    private void checkUnacknowledgedCriticalResults(Instant now) {
        Instant ackCutoff = now.minus(Duration.ofMinutes(CRITICAL_ACK_TIMEOUT_MINUTES));
        List<LabOrder> unacknowledged = labOrderRepository.findUnacknowledgedCriticalResultsBefore(ackCutoff);

        for (LabOrder order : unacknowledged) {
            long minutesSinceResult = Duration.between(order.getResultedAt(), now).toMinutes();

            raiseLabAlert(order, AlertType.CRITICAL_VALUE_UNACKNOWLEDGED, AlertSeverity.CRITICAL,
                    "CRITICAL VALUE UNACKNOWLEDGED: " + order.getTestName(),
                    String.format("CRITICAL lab result for %s (Order %s) has NOT been acknowledged " +
                                    "after %d minutes. Result: %s %s. %s. IMMEDIATE clinician response required.",
                            order.getTestName(), order.getOrderNumber(), minutesSinceResult,
                            order.getResultValue(),
                            order.getResultUnit() != null ? order.getResultUnit() : "",
                            order.getCriticalValueType() != null ? order.getCriticalValueType().getDescription() : ""));

            // Re-broadcast on the lab topic regardless of whether the alert
            // already exists — the doctor's dashboard re-flashes the
            // critical-result banner each cycle the value remains unacked.
            try {
                realTimeEventPublisher.publishLabOrder(
                        order.getVisit().getHospital().getId(),
                        LabOrderMapper.toResponse(order));
            } catch (Exception e) {
                log.warn("Failed to re-broadcast unacked critical lab order {}: {}",
                        order.getOrderNumber(), e.getMessage());
            }
        }
    }

    // ====================================================================
    // SHARED — raise + ROUTE a turnaround/SLA alert to the doctor feed
    // ====================================================================

    /**
     * Create a turnaround/SLA breach alert (deduped per visit + type) AND fan it out to
     * the people who must act — hospital board + the patient's zone + the zone doctor +
     * the ordering doctor + the charge nurse — so it reaches the live alert feed, not just
     * the lab worklist topic. Before this, these rows were saved but only the
     * CRITICAL_VALUE_UNACKNOWLEDGED case ever broadcast (and only to /topic/lab), so an
     * overdue STAT lab never paged a clinician in real time.
     */
    private void raiseLabAlert(LabOrder order, AlertType type, AlertSeverity severity,
                               String title, String message) {
        // Dedupe on ANY ACTIVE alert of this type for the visit — acknowledged OR not.
        // The source queries key on the ORDER still being overdue / its critical value
        // still unacknowledged-at-the-ORDER-level, which is a DIFFERENT flag than the
        // ALERT's acknowledged flag. If we deduped only on UNacknowledged alerts, a
        // clinician acknowledging the alert (without the order condition clearing) would
        // let this method re-create + re-fan-out a fresh alert every 60s — a 5-channel
        // page storm. Suppressing on any active alert of the type prevents that; the
        // time-critical re-escalation loop still re-pages a genuinely unacknowledged one.
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(
                order.getVisit().getId(), type)) {
            return;
        }
        EdZone zone = zoneOf(order.getVisit());
        User zoneDoctor = resolveZoneDoctor(order.getVisit(), zone);

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(order.getVisit())
                .alertType(type)
                .severity(severity)
                .title(title)
                .message(message)
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);
        fanOutLabAlert(alert, order, zone, zoneDoctor);
        log.warn("{} alert raised + routed for order {} (visit {})",
                type, order.getOrderNumber(), order.getVisit().getVisitNumber());
    }

    /**
     * Push a saved lab SLA alert to the doctor alert pipeline (/topic/alerts/*), AFTER the
     * monitor's transaction commits so a rolled-back tick never produces a phantom alert.
     * Recipients: hospital board, the patient's zone, the accountable zone doctor, the
     * ordering doctor (from the linked Investigation), and the charge nurse(s). Best-effort.
     */
    private void fanOutLabAlert(ClinicalAlert alert, LabOrder order, EdZone zone, User zoneDoctor) {
        UUID hospitalId = (order.getVisit() != null && order.getVisit().getHospital() != null)
                ? order.getVisit().getHospital().getId() : null;
        if (hospitalId == null) return;

        final ClinicalAlertResponse resp = ClinicalAlertMapper.toResponse(alert);
        final EdZone z = zone;
        final UUID zoneDoctorId = zoneDoctor != null ? zoneDoctor.getId() : null;
        final UUID orderingDoctorId =
                (order.getInvestigation() != null && order.getInvestigation().getOrderedBy() != null)
                        ? order.getInvestigation().getOrderedBy().getId() : null;
        final List<UUID> chargeNurseIds;
        List<UUID> cn;
        try {
            cn = shiftAssignmentService.getChargeNurse(hospitalId).stream().map(User::getId).toList();
        } catch (Exception e) {
            cn = List.of();
        }
        chargeNurseIds = cn;

        Runnable fire = () -> {
            try {
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (z != null) realTimeEventPublisher.publishZoneAlert(hospitalId, z, resp);
                if (zoneDoctorId != null) realTimeEventPublisher.publishUserAlert(zoneDoctorId, resp);
                if (orderingDoctorId != null && !orderingDoctorId.equals(zoneDoctorId)) {
                    realTimeEventPublisher.publishUserAlert(orderingDoctorId, resp);
                }
                for (UUID id : chargeNurseIds) realTimeEventPublisher.publishUserAlert(id, resp);
            } catch (Exception e) {
                log.warn("Failed to fan out lab SLA alert {} for order {}: {}",
                        alert.getId(), order.getOrderNumber(), e.getMessage());
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

    /** The patient's current ED zone (from triage category), or null if untriaged. */
    private EdZone zoneOf(Visit visit) {
        return visit.getCurrentTriageCategory() != null
                ? EdZone.fromTriageCategory(visit.getCurrentTriageCategory())
                : null;
    }

    /** The accountable zone doctor for the patient's current zone, or null if none on shift. */
    private User resolveZoneDoctor(Visit visit, EdZone zone) {
        try {
            UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
            if (hospitalId == null || zone == null) return null;
            List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
            return doctors.isEmpty() ? null : doctors.get(0);
        } catch (Exception e) {
            return null;
        }
    }
}
