package com.smartTriage.smartTriage_server.module.medication.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.DoseStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationDose;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationDoseRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * MedicationDoseMonitorService (V67) — the clock of the dose workflow.
 * Runs every minute and:
 *
 * <ol>
 *   <li><b>Overdue</b> — a DUE dose past its time + grace window
 *       re-notifies the zone (HIGH alert + WebSocket) once, so the
 *       nurse can act before it becomes a miss;</li>
 *   <li><b>Missed</b> — past the missed threshold the dose is marked
 *       MISSED, escalated to the charge nurse (CRITICAL alert), and —
 *       crucially — the SCHEDULE ROLLS FORWARD so one missed dose
 *       never silently kills the rest of the course;</li>
 *   <li><b>Completion</b> — live recurring/continuous orders whose
 *       end time has passed are closed out as COMPLETED.</li>
 * </ol>
 *
 * <p>Alert creation is de-duplicated per visit + alert type (same
 * convention as the STAT/URGENT SLA monitor) so the 60-second tick
 * never stacks copies; WebSocket events publish on every transition
 * regardless, because boards refetch idempotently.
 *
 * <p>Complements (does not replace) {@code MedicationStatMonitorService},
 * which watches order-level STAT/URGENT SLA on the legacy queue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationDoseMonitorService {

    private final MedicationDoseRepository doseRepository;
    private final MedicationAdministrationRepository medicationRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final MedicationScheduleService scheduleService;

    /** Minutes past due before the overdue re-notification fires. */
    @Value("${smarttriage.medication.dose-overdue-grace-minutes:15}")
    private int overdueGraceMinutes;

    /** Minutes past due before the dose is declared MISSED + escalated. */
    @Value("${smarttriage.medication.dose-missed-minutes:60}")
    private int missedThresholdMinutes;

    @Scheduled(fixedDelayString = "${smarttriage.medication.dose-monitor-interval-ms:60000}")
    @Transactional
    public void tick() {
        Instant now = Instant.now();
        try {
            sweepMissed(now);
            sweepOverdue(now);
            sweepCompletedOrders(now);
        } catch (Exception e) {
            log.warn("MedicationDoseMonitorService tick failed: {}", e.getMessage(), e);
        }
    }

    /**
     * MISSED before OVERDUE on purpose: a dose that crossed both
     * thresholds between ticks goes straight to MISSED instead of
     * burning one tick on an overdue notification it already outlived.
     */
    private void sweepMissed(Instant now) {
        Instant cutoff = now.minus(Duration.ofMinutes(missedThresholdMinutes));
        List<MedicationDose> missed = doseRepository.findDueBefore(cutoff);
        for (MedicationDose dose : missed) {
            MedicationAdministration order = dose.getMedication();
            Visit visit = dose.getVisit();

            dose.setStatus(DoseStatus.MISSED);
            dose.setMissedEscalatedAt(now);
            dose.appendStatusReason(String.format(
                    "MISSED — not administered within %d min of due time %s",
                    missedThresholdMinutes, dose.getDueAt()));
            doseRepository.save(dose);

            long minutesLate = dose.getDueAt() != null
                    ? Duration.between(dose.getDueAt(), now).toMinutes() : 0;
            log.warn("DOSE MISSED — order:{} drug:{} visit:{} {} min late",
                    order.getId(), order.getDrugName(), visit.getVisitNumber(), minutesLate);

            boolean alreadyOpen = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            visit.getId(), AlertType.MEDICATION_DOSE_MISSED);
            if (!alreadyOpen) {
                ClinicalAlert alert = ClinicalAlert.builder()
                        .visit(visit)
                        .alertType(AlertType.MEDICATION_DOSE_MISSED)
                        .severity(AlertSeverity.CRITICAL)
                        .title("MISSED DOSE: " + order.getDrugName())
                        .message(String.format(
                                "Dose #%s of %s%s (visit %s) was due %s and was never given, "
                                        + "refused, or held — %d minutes late. Charge nurse review "
                                        + "required: document the miss and confirm the next dose.",
                                dose.getSequenceNumber() != null
                                        ? dose.getSequenceNumber() : "?",
                                order.getDrugName(),
                                order.getDose() != null ? " " + order.getDose() : "",
                                visit.getVisitNumber(),
                                dose.getDueAt(),
                                minutesLate))
                        .autoGenerated(true)
                        .build();
                clinicalAlertRepository.save(alert);
            }

            // Keep the course alive: the NEXT dose is scheduled from the
            // missed dose's slot, never silently dropped.
            scheduleService.rollScheduleForward(order,
                    dose.getDueAt() != null ? dose.getDueAt() : now);

            publishDoseEvent(dose, "DOSE_MISSED");
        }
    }

    private void sweepOverdue(Instant now) {
        Instant cutoff = now.minus(Duration.ofMinutes(overdueGraceMinutes));
        List<MedicationDose> overdue = doseRepository.findDueBefore(cutoff);
        for (MedicationDose dose : overdue) {
            if (dose.getOverdueNotifiedAt() != null) continue; // already notified
            MedicationAdministration order = dose.getMedication();
            Visit visit = dose.getVisit();

            dose.setOverdueNotifiedAt(now);
            doseRepository.save(dose);

            long minutesLate = dose.getDueAt() != null
                    ? Duration.between(dose.getDueAt(), now).toMinutes() : 0;
            log.warn("DOSE OVERDUE — order:{} drug:{} visit:{} {} min late",
                    order.getId(), order.getDrugName(), visit.getVisitNumber(), minutesLate);

            boolean alreadyOpen = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            visit.getId(), AlertType.MEDICATION_DOSE_OVERDUE);
            if (!alreadyOpen) {
                ClinicalAlert alert = ClinicalAlert.builder()
                        .visit(visit)
                        .alertType(AlertType.MEDICATION_DOSE_OVERDUE)
                        .severity(AlertSeverity.HIGH)
                        .title("Dose overdue: " + order.getDrugName())
                        .message(String.format(
                                "Dose #%s of %s%s (visit %s) was due %s — %d minutes ago. "
                                        + "Administer it, delay it with a reason, or record a "
                                        + "refusal before it escalates as MISSED at %d minutes.",
                                dose.getSequenceNumber() != null
                                        ? dose.getSequenceNumber() : "?",
                                order.getDrugName(),
                                order.getDose() != null ? " " + order.getDose() : "",
                                visit.getVisitNumber(),
                                dose.getDueAt(),
                                minutesLate,
                                missedThresholdMinutes))
                        .autoGenerated(true)
                        .build();
                clinicalAlertRepository.save(alert);
            }

            publishDoseEvent(dose, "DOSE_OVERDUE");
        }
    }

    /** Close out recurring/continuous orders whose endAt has passed. */
    private void sweepCompletedOrders(Instant now) {
        List<MedicationAdministration> ended =
                medicationRepository.findLiveTypedOrdersPastEnd(now);
        for (MedicationAdministration order : ended) {
            scheduleService.completeOrder(order, "Scheduled duration elapsed");
        }
    }

    private void publishDoseEvent(MedicationDose dose, String eventType) {
        try {
            Visit visit = dose.getVisit();
            if (visit == null || visit.getHospital() == null) return;
            realTimeEventPublisher.publishMedicationEvent(
                    visit.getHospital().getId(),
                    visit.getCurrentEdZone(),
                    scheduleService.doseEventPayload(dose, eventType));
        } catch (Exception e) {
            log.warn("Failed to publish {} for dose {}: {}",
                    eventType, dose.getId(), e.getMessage());
        }
    }
}
