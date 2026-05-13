package com.smartTriage.smartTriage_server.module.lab.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.mapper.LabOrderMapper;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    private static final int STAT_OVERDUE_MINUTES = 30;
    private static final int URGENT_OVERDUE_MINUTES = 120;
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

            // Deduplicate against an open unacknowledged LAB_NOT_RECEIVED
            // alert on the same visit. Doesn't filter by order-id because
            // the alert pipeline is visit-scoped; the message text
            // identifies which order this is about.
            boolean alertExists = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            order.getVisit().getId(), AlertType.LAB_NOT_RECEIVED);

            if (!alertExists) {
                ClinicalAlert alert = ClinicalAlert.builder()
                        .visit(order.getVisit())
                        .alertType(AlertType.LAB_NOT_RECEIVED)
                        .severity(severity)
                        .title(priority.name() + " specimen not received: " + order.getTestName())
                        .message(String.format(
                                "%s lab order %s (%s) has been in ORDERED status for %d min " +
                                        "(threshold: %d min, total SLA: %d min). The specimen has not " +
                                        "been received by the lab yet — collect and deliver to avoid " +
                                        "missing the turnaround target. Ordered by %s.",
                                priority.name(),
                                order.getOrderNumber(),
                                order.getTestName(),
                                minutesWaiting,
                                thresholdMinutes,
                                priority.getTargetMinutes(),
                                order.getOrderedByName() != null ? order.getOrderedByName() : "unknown"))
                        .autoGenerated(true)
                        .build();

                clinicalAlertRepository.save(alert);
                log.warn("LAB_NOT_RECEIVED alert for order {} (priority {}) — {} min in ORDERED",
                        order.getOrderNumber(), priority, minutesWaiting);
            }
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

            // Avoid duplicate alerts — check if one already exists for this order
            boolean alertExists = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            order.getVisit().getId(), AlertType.STAT_LAB_OVERDUE);

            if (!alertExists) {
                ClinicalAlert alert = ClinicalAlert.builder()
                        .visit(order.getVisit())
                        .alertType(AlertType.STAT_LAB_OVERDUE)
                        .severity(AlertSeverity.HIGH)
                        .title("STAT LAB OVERDUE: " + order.getTestName())
                        .message(String.format("STAT lab order %s (%s) has been waiting %d minutes " +
                                        "(target: %d min). Ordered by %s.",
                                order.getOrderNumber(),
                                order.getTestName(),
                                minutesWaiting,
                                STAT_OVERDUE_MINUTES,
                                order.getOrderedByName() != null ? order.getOrderedByName() : "unknown"))
                        .autoGenerated(true)
                        .build();

                clinicalAlertRepository.save(alert);
                log.warn("STAT LAB OVERDUE alert for order {} — {} min waiting",
                        order.getOrderNumber(), minutesWaiting);
            }
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

            boolean alertExists = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            order.getVisit().getId(), AlertType.URGENT_LAB_OVERDUE);

            if (!alertExists) {
                ClinicalAlert alert = ClinicalAlert.builder()
                        .visit(order.getVisit())
                        .alertType(AlertType.URGENT_LAB_OVERDUE)
                        .severity(AlertSeverity.HIGH)
                        .title("URGENT LAB OVERDUE: " + order.getTestName())
                        .message(String.format("URGENT lab order %s (%s) has been waiting %d minutes " +
                                        "(target: %d min). Ordered by %s.",
                                order.getOrderNumber(),
                                order.getTestName(),
                                minutesWaiting,
                                URGENT_OVERDUE_MINUTES,
                                order.getOrderedByName() != null ? order.getOrderedByName() : "unknown"))
                        .autoGenerated(true)
                        .build();

                clinicalAlertRepository.save(alert);
                log.warn("URGENT LAB OVERDUE alert for order {} — {} min waiting",
                        order.getOrderNumber(), minutesWaiting);
            }
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

            boolean alertExists = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            order.getVisit().getId(), AlertType.CRITICAL_VALUE_UNACKNOWLEDGED);

            if (!alertExists) {
                ClinicalAlert alert = ClinicalAlert.builder()
                        .visit(order.getVisit())
                        .alertType(AlertType.CRITICAL_VALUE_UNACKNOWLEDGED)
                        .severity(AlertSeverity.CRITICAL)
                        .title("CRITICAL VALUE UNACKNOWLEDGED: " + order.getTestName())
                        .message(String.format("CRITICAL lab result for %s (Order %s) has NOT been acknowledged " +
                                        "after %d minutes. Result: %s %s. %s. " +
                                        "IMMEDIATE clinician response required.",
                                order.getTestName(),
                                order.getOrderNumber(),
                                minutesSinceResult,
                                order.getResultValue(),
                                order.getResultUnit() != null ? order.getResultUnit() : "",
                                order.getCriticalValueType() != null ? order.getCriticalValueType().getDescription() : ""))
                        .autoGenerated(true)
                        .build();

                clinicalAlertRepository.save(alert);
                log.warn("CRITICAL VALUE UNACKNOWLEDGED escalation for order {} — {} min since result",
                        order.getOrderNumber(), minutesSinceResult);
            }

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
}
