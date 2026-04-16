package com.smartTriage.smartTriage_server.module.retriage.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.retriage.dto.OverduePatientResponse;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ReassessmentSchedulerService — monitors patients for periodic reassessment
 * based on their triage category.
 *
 * Reassessment intervals (from SATS protocol):
 *   RED    = 0 min (continuous monitoring)
 *   ORANGE = every 10 min
 *   YELLOW = every 30 min
 *   GREEN  = every 60 min
 *
 * Generates REASSESSMENT_DUE alerts when a patient's last triage time
 * exceeds the reassessment interval for their category.
 *
 * Runs every 120 seconds. Prevents duplicate alerts by checking for existing
 * unacknowledged REASSESSMENT_DUE alerts for each visit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReassessmentSchedulerService {

    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;

    /**
     * Statuses that require reassessment monitoring — patients still in the ED workflow.
     */
    private static final List<VisitStatus> MONITORED_STATUSES = List.of(
            VisitStatus.AWAITING_TRIAGE,
            VisitStatus.TRIAGED,
            VisitStatus.AWAITING_ASSESSMENT,
            VisitStatus.UNDER_ASSESSMENT,
            VisitStatus.UNDER_TREATMENT,
            VisitStatus.UNDER_OBSERVATION
    );

    /**
     * Scheduled task: check all active visits for overdue reassessment every 120 seconds.
     */
    @Scheduled(fixedDelayString = "${smarttriage.retriage.reassessment-check-interval-ms:120000}")
    @Transactional
    public void checkReassessments() {
        List<Visit> activeVisits = visitRepository.findAllActiveVisitsByStatuses(MONITORED_STATUSES);

        int alertsGenerated = 0;
        for (Visit visit : activeVisits) {
            if (processVisitReassessment(visit)) {
                alertsGenerated++;
            }
        }

        if (alertsGenerated > 0) {
            log.info("Reassessment scheduler: generated {} REASSESSMENT_DUE alerts", alertsGenerated);
        }
    }

    /**
     * Check a single visit for overdue reassessment and generate alert if needed.
     *
     * @return true if a new alert was generated
     */
    private boolean processVisitReassessment(Visit visit) {
        TriageCategory category = visit.getCurrentTriageCategory();
        if (category == null || category == TriageCategory.BLUE) {
            return false;
        }

        int reassessmentIntervalMinutes = category.getMaxWaitMinutes();

        // Get the last triage time from the most recent triage record
        TriageRecord lastTriage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visit.getId())
                .orElse(null);

        Instant lastTriageTime = lastTriage != null ? lastTriage.getTriageTime() : visit.getTriageTime();
        if (lastTriageTime == null) {
            // Patient hasn't been triaged yet — skip reassessment check
            return false;
        }

        long minutesSinceLastTriage = Duration.between(lastTriageTime, Instant.now()).toMinutes();

        // RED patients: continuous (0 min) — always overdue if any time has passed since triage
        // For other categories: overdue when minutes since last triage exceeds the interval
        if (minutesSinceLastTriage <= reassessmentIntervalMinutes) {
            return false;
        }

        // Check for existing unacknowledged alert to avoid duplicates
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.REASSESSMENT_DUE)) {
            return false;
        }

        // Determine severity based on how overdue
        AlertSeverity severity;
        if (category == TriageCategory.RED) {
            severity = AlertSeverity.CRITICAL;
        } else if (minutesSinceLastTriage >= (long) reassessmentIntervalMinutes * 2) {
            severity = AlertSeverity.CRITICAL;
        } else {
            severity = AlertSeverity.HIGH;
        }

        String patientName = visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
        Instant nextReassessmentDue = lastTriageTime.plus(reassessmentIntervalMinutes, ChronoUnit.MINUTES);

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.REASSESSMENT_DUE)
                .severity(severity)
                .title("REASSESSMENT DUE — " + category.name() + " Patient")
                .message(String.format(
                        "Patient %s (Visit: %s) is overdue for reassessment. " +
                        "Category: %s (%s). Last triaged %d minutes ago. " +
                        "Reassessment interval: every %d minutes. " +
                        "Reassessment was due at %s. %s",
                        patientName,
                        visit.getVisitNumber(),
                        category.name(),
                        category.getDescription(),
                        minutesSinceLastTriage,
                        reassessmentIntervalMinutes,
                        nextReassessmentDue,
                        severity == AlertSeverity.CRITICAL
                                ? "CRITICAL: Patient is 2x+ overdue for reassessment."
                                : "Patient requires reassessment."))
                .autoGenerated(true)
                .satsTargetMinutes(reassessmentIntervalMinutes)
                .build();

        clinicalAlertRepository.save(alert);

        log.warn("REASSESSMENT_DUE: Visit {} | Category: {} | Last triage: {} min ago | Interval: {} min | Severity: {}",
                visit.getVisitNumber(), category.name(), minutesSinceLastTriage,
                reassessmentIntervalMinutes, severity);

        return true;
    }

    /**
     * Get all patients overdue for reassessment at a specific hospital.
     * Used by the retriage controller for dashboard queries.
     */
    @Transactional(readOnly = true)
    public List<OverduePatientResponse> getOverdueReassessments(UUID hospitalId) {
        List<Visit> activeVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, MONITORED_STATUSES);
        List<OverduePatientResponse> overduePatients = new ArrayList<>();

        for (Visit visit : activeVisits) {
            TriageCategory category = visit.getCurrentTriageCategory();
            if (category == null || category == TriageCategory.BLUE) {
                continue;
            }

            int reassessmentIntervalMinutes = category.getMaxWaitMinutes();

            TriageRecord lastTriage = triageRecordRepository
                    .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visit.getId())
                    .orElse(null);

            Instant lastTriageTime = lastTriage != null ? lastTriage.getTriageTime() : visit.getTriageTime();
            if (lastTriageTime == null) {
                continue;
            }

            long minutesSinceLastTriage = Duration.between(lastTriageTime, Instant.now()).toMinutes();

            if (minutesSinceLastTriage > reassessmentIntervalMinutes) {
                Instant nextReassessmentDue = lastTriageTime.plus(reassessmentIntervalMinutes, ChronoUnit.MINUTES);
                long overdueBy = minutesSinceLastTriage - reassessmentIntervalMinutes;

                String severity;
                if (category == TriageCategory.RED || minutesSinceLastTriage >= (long) reassessmentIntervalMinutes * 2) {
                    severity = "CRITICAL";
                } else {
                    severity = "HIGH";
                }

                overduePatients.add(OverduePatientResponse.builder()
                        .visitId(visit.getId())
                        .visitNumber(visit.getVisitNumber())
                        .patientName(visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName())
                        .currentCategory(category)
                        .tewsScore(visit.getCurrentTewsScore())
                        .lastTriageTime(lastTriageTime)
                        .nextReassessmentDue(nextReassessmentDue)
                        .waitTimeMinutes(minutesSinceLastTriage)
                        .maxWaitMinutes(reassessmentIntervalMinutes)
                        .overdueByMinutes(overdueBy)
                        .alertSeverity(severity)
                        .build());
            }
        }

        return overduePatients;
    }
}
