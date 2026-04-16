package com.smartTriage.smartTriage_server.module.retriage.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.retriage.dto.OverduePatientResponse;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WaitingTimeMonitorService — scheduled service that monitors all active visits
 * and generates WAITING_TIME_EXCEEDED alerts when patients have waited beyond
 * their SATS target time.
 *
 * SATS wait time targets (from TriageCategory):
 *   RED    = 0 min (immediate)
 *   ORANGE = 10 min
 *   YELLOW = 30 min
 *   GREEN  = 60 min
 *
 * Escalation logic:
 *   1x target exceeded → HIGH severity
 *   2x target exceeded → CRITICAL severity
 *
 * Runs every 60 seconds. Does not duplicate alerts (checks for existing
 * unacknowledged WAITING_TIME_EXCEEDED alert before creating a new one).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitingTimeMonitorService {

    private final VisitRepository visitRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;

    private static final List<VisitStatus> MONITORED_STATUSES = List.of(
            VisitStatus.AWAITING_TRIAGE,
            VisitStatus.TRIAGED
    );

    /**
     * Scheduled task: check all active visits for wait time violations every 60 seconds.
     */
    @Scheduled(fixedDelayString = "${smarttriage.retriage.waiting-check-interval-ms:60000}")
    @Transactional
    public void checkWaitingTimes() {
        List<Visit> activeVisits = visitRepository.findAllActiveVisitsByStatuses(MONITORED_STATUSES);

        int alertsGenerated = 0;
        for (Visit visit : activeVisits) {
            if (processVisitWaitTime(visit)) {
                alertsGenerated++;
            }
        }

        if (alertsGenerated > 0) {
            log.info("Waiting time monitor: generated {} WAITING_TIME_EXCEEDED alerts", alertsGenerated);
        }
    }

    /**
     * Check a single visit's wait time and generate alert if exceeded.
     *
     * @return true if a new alert was generated
     */
    private boolean processVisitWaitTime(Visit visit) {
        TriageCategory category = visit.getCurrentTriageCategory();
        if (category == null || category == TriageCategory.BLUE) {
            return false;
        }

        int maxWaitMinutes = category.getMaxWaitMinutes();

        // RED category has 0-minute target — always exceeded if still waiting
        // For pre-triage patients, use arrival time; for triaged patients, use triage time
        Instant waitStartTime = visit.getTriageTime() != null
                ? visit.getTriageTime()
                : visit.getArrivalTime();

        long waitMinutes = Duration.between(waitStartTime, Instant.now()).toMinutes();

        if (waitMinutes <= maxWaitMinutes) {
            return false; // Within target — no alert needed
        }

        // Check for existing unacknowledged alert to avoid duplicates
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.WAITING_TIME_EXCEEDED)) {
            return false;
        }

        // Determine escalation severity
        AlertSeverity severity;
        if (maxWaitMinutes == 0 || waitMinutes >= (long) maxWaitMinutes * 2) {
            severity = AlertSeverity.CRITICAL;
        } else {
            severity = AlertSeverity.HIGH;
        }

        // Generate alert
        String patientName = visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.WAITING_TIME_EXCEEDED)
                .severity(severity)
                .title("WAITING TIME EXCEEDED — " + category.name() + " (" + category.getDescription() + ")")
                .message(String.format(
                        "Patient %s (Visit: %s) has been waiting %d minutes. " +
                        "SATS target for %s category is %d minutes. " +
                        "Wait time is %.1fx the target. %s",
                        patientName,
                        visit.getVisitNumber(),
                        waitMinutes,
                        category.name(),
                        maxWaitMinutes,
                        maxWaitMinutes > 0 ? (double) waitMinutes / maxWaitMinutes : waitMinutes,
                        severity == AlertSeverity.CRITICAL
                                ? "CRITICAL ESCALATION: Patient has waited 2x+ target time."
                                : "Patient requires immediate attention."))
                .autoGenerated(true)
                .satsTargetMinutes(maxWaitMinutes)
                .build();

        clinicalAlertRepository.save(alert);

        log.warn("WAITING_TIME_EXCEEDED: Visit {} | Category: {} | Wait: {} min | Target: {} min | Severity: {}",
                visit.getVisitNumber(), category.name(), waitMinutes, maxWaitMinutes, severity);

        return true;
    }

    /**
     * Get all patients who have exceeded their wait time for a specific hospital.
     * Used by the retriage controller for dashboard queries.
     */
    @Transactional(readOnly = true)
    public List<OverduePatientResponse> getWaitTimeExceededPatients(UUID hospitalId) {
        List<Visit> activeVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, MONITORED_STATUSES);
        List<OverduePatientResponse> overduePatients = new ArrayList<>();

        for (Visit visit : activeVisits) {
            TriageCategory category = visit.getCurrentTriageCategory();
            if (category == null || category == TriageCategory.BLUE) {
                continue;
            }

            int maxWaitMinutes = category.getMaxWaitMinutes();
            Instant waitStartTime = visit.getTriageTime() != null
                    ? visit.getTriageTime()
                    : visit.getArrivalTime();

            long waitMinutes = Duration.between(waitStartTime, Instant.now()).toMinutes();

            if (waitMinutes > maxWaitMinutes) {
                long overdueBy = waitMinutes - maxWaitMinutes;
                String severity = (maxWaitMinutes == 0 || waitMinutes >= (long) maxWaitMinutes * 2)
                        ? "CRITICAL" : "HIGH";

                overduePatients.add(OverduePatientResponse.builder()
                        .visitId(visit.getId())
                        .visitNumber(visit.getVisitNumber())
                        .patientName(visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName())
                        .currentCategory(category)
                        .tewsScore(visit.getCurrentTewsScore())
                        .lastTriageTime(visit.getTriageTime())
                        .waitTimeMinutes(waitMinutes)
                        .maxWaitMinutes(maxWaitMinutes)
                        .overdueByMinutes(overdueBy)
                        .alertSeverity(severity)
                        .build());
            }
        }

        return overduePatients;
    }
}
