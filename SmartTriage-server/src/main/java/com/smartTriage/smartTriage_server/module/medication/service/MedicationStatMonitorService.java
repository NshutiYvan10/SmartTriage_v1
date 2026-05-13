package com.smartTriage.smartTriage_server.module.medication.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.MedicationPriority;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.medication.entity.MedicationAdministration;
import com.smartTriage.smartTriage_server.module.medication.repository.MedicationAdministrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * MedicationStatMonitorService — periodic scheduler that escalates
 * STAT and URGENT medications that have been sitting in PRESCRIBED
 * status past their administration SLA.
 *
 * <p>Mirrors the {@code LabTurnaroundMonitorService} pattern. Runs
 * every 60 seconds and:
 *
 * <ul>
 *   <li>STAT meds older than {@value #STAT_OVERDUE_MINUTES} min
 *       → CRITICAL {@link AlertType#STAT_MEDICATION_OVERDUE} alert;</li>
 *   <li>URGENT meds older than {@value #URGENT_OVERDUE_MINUTES} min
 *       → HIGH {@link AlertType#URGENT_MEDICATION_OVERDUE} alert.</li>
 * </ul>
 *
 * <p>Deduplicated per visit + alert type so the scheduler doesn't
 * stack copies on every tick — the nurse acknowledges once, and the
 * monitor only re-fires once the alert has been resolved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationStatMonitorService {

    private final MedicationAdministrationRepository medicationRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;

    /** SLA window before a STAT med is flagged as overdue. */
    static final int STAT_OVERDUE_MINUTES = 10;
    /** SLA window before an URGENT med is flagged as overdue. */
    static final int URGENT_OVERDUE_MINUTES = 30;

    @Scheduled(fixedDelayString = "${smarttriage.medication.stat-monitor-interval-ms:60000}")
    @Transactional
    public void tick() {
        Instant now = Instant.now();
        try {
            checkOverdue(now, MedicationPriority.STAT, STAT_OVERDUE_MINUTES,
                    AlertType.STAT_MEDICATION_OVERDUE, AlertSeverity.CRITICAL,
                    "STAT MEDICATION OVERDUE");
            checkOverdue(now, MedicationPriority.URGENT, URGENT_OVERDUE_MINUTES,
                    AlertType.URGENT_MEDICATION_OVERDUE, AlertSeverity.HIGH,
                    "URGENT medication overdue");
        } catch (Exception e) {
            log.warn("MedicationStatMonitorService tick failed: {}", e.getMessage(), e);
        }
    }

    private void checkOverdue(
            Instant now,
            MedicationPriority priority,
            int thresholdMinutes,
            AlertType alertType,
            AlertSeverity severity,
            String titlePrefix) {

        Instant cutoff = now.minus(Duration.ofMinutes(thresholdMinutes));
        List<MedicationAdministration> overdue =
                medicationRepository.findOverduePrescribedByPriority(priority, cutoff);

        for (MedicationAdministration med : overdue) {
            if (med.getVisit() == null) continue;
            long minutesWaiting = Duration.between(med.getPrescribedAt(), now).toMinutes();

            // Deduplicate per visit + alert type. The nurse closes the
            // loop by acknowledging the alert (or administering the
            // drug, which removes it from the overdue query). Either
            // way we don't stack alerts on every 60-second tick.
            boolean alreadyOpen = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            med.getVisit().getId(), alertType);
            if (alreadyOpen) continue;

            String prescriberName = med.getPrescribedByName() != null
                    ? med.getPrescribedByName() : "(unknown prescriber)";
            String visitNumber = med.getVisit().getVisitNumber();

            ClinicalAlert alert = ClinicalAlert.builder()
                    .visit(med.getVisit())
                    .alertType(alertType)
                    .severity(severity)
                    .title(titlePrefix + ": " + med.getDrugName())
                    .message(String.format(
                            "%s order for %s (visit %s) has been waiting %d minutes since "
                                    + "%s prescribed it (SLA: %d min). Administer immediately or "
                                    + "document a hold/refuse reason.",
                            priority.name(),
                            med.getDrugName()
                                    + (med.getDose() != null ? " " + med.getDose() : ""),
                            visitNumber,
                            minutesWaiting,
                            prescriberName,
                            thresholdMinutes))
                    .autoGenerated(true)
                    .build();

            clinicalAlertRepository.save(alert);
            log.warn("{} alert created — med:{} drug:'{}' visit:{} waiting:{}min",
                    alertType, med.getId(), med.getDrugName(), visitNumber, minutesWaiting);
        }
    }
}
