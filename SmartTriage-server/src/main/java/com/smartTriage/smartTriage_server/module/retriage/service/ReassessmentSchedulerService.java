package com.smartTriage.smartTriage_server.module.retriage.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.retriage.dto.OverduePatientResponse;
import com.smartTriage.smartTriage_server.module.retriage.dto.RecheckWorklistItem;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ReassessmentSchedulerService — the vitals-recheck clock for patients
 * who are NOT on a continuous monitor (chair-based GENERAL/AMBULATORY
 * patients above all).
 *
 * <p><b>Clock basis (vitals-aware):</b> a patient counts as "assessed"
 * at the LATER of their last triage and their last recorded clinical
 * vitals (manual entry, triage obs, or a completed roaming spot-check —
 * all of which produce a {@code VitalSigns} row). The clock is derived,
 * never stored, so it can't drift out of sync with the chart.
 *
 * <p><b>Recheck intervals (ratified 2026-07-27):</b>
 * <ul>
 *   <li>RED    — 0 min (belongs on a continuous monitor; unmonitored RED
 *                is immediately overdue, CRITICAL)</li>
 *   <li>ORANGE — 30 min</li>
 *   <li>YELLOW — 60 min</li>
 *   <li>GREEN  — 120 min</li>
 * </ul>
 *
 * <p><b>Session-aware:</b> visits with an ACTIVE monitoring session are
 * skipped — a continuous stream IS continuous reassessment, and a
 * spot-check in progress means a nurse is already at the chair. (Before
 * this rework the scheduler nagged monitored patients too.)
 *
 * <p><b>Self-clearing:</b> when vitals are recorded, the open
 * REASSESSMENT_DUE alert is acknowledged by the system on the next sweep
 * — the nag disappears because the work was done, not because someone
 * clicked it away.
 *
 * <p>Runs every 120 seconds; duplicate alerts prevented per visit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReassessmentSchedulerService {

    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final DeviceSessionRepository deviceSessionRepository;

    /**
     * Ratified recheck intervals (minutes) per triage category. Distinct
     * from {@link TriageCategory#getMaxWaitMinutes()} — that is the
     * time-to-first-contact target; this is the repeat-obs cadence for
     * patients already in the department.
     */
    private static final Map<TriageCategory, Integer> RECHECK_INTERVAL_MINUTES = Map.of(
            TriageCategory.RED, 0,
            TriageCategory.ORANGE, 30,
            TriageCategory.YELLOW, 60,
            TriageCategory.GREEN, 120);

    /**
     * Statuses that require reassessment monitoring — patients still in the ED workflow.
     */
    private static final List<VisitStatus> MONITORED_STATUSES = List.of(
            VisitStatus.AWAITING_TRIAGE,
            VisitStatus.TRIAGED,
            VisitStatus.AWAITING_ASSESSMENT,
            VisitStatus.UNDER_ASSESSMENT,
            VisitStatus.UNDER_TREATMENT,
            VisitStatus.UNDER_OBSERVATION);

    /**
     * Scheduled task: check all active visits for overdue reassessment every 120 seconds.
     */
    @Scheduled(fixedDelayString = "${smarttriage.retriage.reassessment-check-interval-ms:120000}")
    @Transactional
    public void checkReassessments() {
        List<Visit> activeVisits = visitRepository.findAllActiveVisitsByStatuses(MONITORED_STATUSES);

        int alertsGenerated = 0;
        int alertsCleared = 0;
        for (Visit visit : activeVisits) {
            try {
                int delta = processVisitReassessment(visit);
                if (delta > 0) alertsGenerated++;
                if (delta < 0) alertsCleared++;
            } catch (Exception e) {
                log.error("Reassessment check failed for visit {}: {}",
                        visit.getVisitNumber(), e.getMessage());
            }
        }

        if (alertsGenerated > 0 || alertsCleared > 0) {
            log.info("Reassessment scheduler: {} REASSESSMENT_DUE alerts raised, {} self-cleared",
                    alertsGenerated, alertsCleared);
        }
    }

    /**
     * Evaluate one visit. Returns +1 when a new alert was raised, −1 when
     * an open alert self-cleared, 0 otherwise.
     */
    private int processVisitReassessment(Visit visit) {
        RecheckWorklistItem item = evaluate(visit);
        if (item == null) {
            // Not clocked (no category / BLUE / never assessed) or covered
            // by an active session — self-clear any leftover nag either way.
            return selfClearOpenAlert(visit) ? -1 : 0;
        }

        if (!item.isOverdue() || item.isCheckInProgress()) {
            return selfClearOpenAlert(visit) ? -1 : 0;
        }

        // Overdue — dedup against an existing unacknowledged alert.
        if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                visit.getId(), AlertType.REASSESSMENT_DUE)) {
            return 0;
        }

        TriageCategory category = item.getCategory();
        long overdueBy = -item.getMinutesUntilDue();
        AlertSeverity severity =
                (category == TriageCategory.RED
                        || (item.getIntervalMinutes() > 0
                            && overdueBy >= item.getIntervalMinutes()))
                        ? AlertSeverity.CRITICAL
                        : AlertSeverity.HIGH;

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.REASSESSMENT_DUE)
                .severity(severity)
                .title("VITALS RECHECK DUE — " + category.name() + " patient")
                .message(String.format(
                        "Patient %s (Visit: %s) is overdue for a vitals recheck. " +
                        "Category: %s — recheck every %d min. Last assessed %d min ago " +
                        "(due at %s, overdue by %d min). Take the roaming monitor to the " +
                        "patient or record vitals manually; recording vitals clears this alert.",
                        item.getPatientName(),
                        visit.getVisitNumber(),
                        category.name(),
                        item.getIntervalMinutes(),
                        item.getIntervalMinutes() + overdueBy,
                        item.getNextDueAt(),
                        overdueBy))
                .autoGenerated(true)
                .satsTargetMinutes(item.getIntervalMinutes())
                .build();

        clinicalAlertRepository.save(alert);

        log.warn("REASSESSMENT_DUE: Visit {} | Category: {} | overdue by {} min | Severity: {}",
                visit.getVisitNumber(), category.name(), overdueBy, severity);
        return 1;
    }

    /** Acknowledge an open REASSESSMENT_DUE alert as done-by-recording-vitals. */
    private boolean selfClearOpenAlert(Visit visit) {
        Optional<ClinicalAlert> open = clinicalAlertRepository
                .findFirstByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                        visit.getId(), AlertType.REASSESSMENT_DUE);
        if (open.isEmpty()) return false;
        ClinicalAlert alert = open.get();
        alert.setAcknowledged(true);
        alert.setAcknowledgedAt(Instant.now());
        alert.setAcknowledgmentNote("Self-cleared: patient reassessed (vitals recorded or monitoring active)");
        clinicalAlertRepository.save(alert);
        return true;
    }

    // ====================================================================
    // WORKLIST / QUERY SIDE
    // ====================================================================

    /**
     * The vitals-round worklist for a hospital: every clocked patient
     * (not on a continuous monitor) with due/overdue state, soonest-due
     * first. Optional zone filter for zone-scoped views.
     */
    @Transactional(readOnly = true)
    public List<RecheckWorklistItem> getRecheckWorklist(UUID hospitalId, EdZone zone) {
        List<Visit> activeVisits = visitRepository.findActiveVisitsByStatuses(hospitalId, MONITORED_STATUSES);
        List<RecheckWorklistItem> items = new ArrayList<>();
        for (Visit visit : activeVisits) {
            if (zone != null && visit.getCurrentEdZone() != zone) continue;
            RecheckWorklistItem item = evaluate(visit);
            if (item != null) items.add(item);
        }
        items.sort((a, b) -> Long.compare(a.getMinutesUntilDue(), b.getMinutesUntilDue()));
        return items;
    }

    /**
     * Core clock evaluation for one visit. Returns null when the visit is
     * not clocked: no category yet, BLUE, never assessed at all, or
     * covered by an active CONTINUOUS monitoring session. A visit with an
     * active SPOT_CHECK session IS returned, flagged checkInProgress.
     */
    private RecheckWorklistItem evaluate(Visit visit) {
        TriageCategory category = visit.getCurrentTriageCategory();
        if (category == null || category == TriageCategory.BLUE) return null;

        Integer interval = RECHECK_INTERVAL_MINUTES.get(category);
        if (interval == null) return null;

        Instant lastAssessed = lastAssessedAt(visit);
        if (lastAssessed == null) return null; // pre-triage — not clocked yet

        boolean checkInProgress = false;
        Optional<DeviceSession> activeSession = deviceSessionRepository
                .findByVisitIdAndSessionActiveTrueAndIsActiveTrue(visit.getId());
        if (activeSession.isPresent()) {
            if (activeSession.get().getSessionType() == SessionType.SPOT_CHECK) {
                checkInProgress = true;
            } else {
                // Continuous stream = continuous reassessment.
                return null;
            }
        }

        Instant nextDue = lastAssessed.plus(interval, ChronoUnit.MINUTES);
        long minutesUntilDue = Duration.between(Instant.now(), nextDue).toMinutes();

        return RecheckWorklistItem.builder()
                .visitId(visit.getId())
                .visitNumber(visit.getVisitNumber())
                .patientName(visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName())
                .pediatric(visit.isPediatric())
                .category(category)
                .tewsScore(visit.getCurrentTewsScore())
                .zone(visit.getCurrentEdZone())
                .bedCode(visit.getCurrentBed() != null ? visit.getCurrentBed().getCode() : null)
                .lastAssessedAt(lastAssessed)
                .nextDueAt(nextDue)
                .intervalMinutes(interval)
                .minutesUntilDue(minutesUntilDue)
                .overdue(minutesUntilDue < 0)
                .checkInProgress(checkInProgress)
                .build();
    }

    /**
     * Vitals-aware assessment basis: the LATER of the last triage time
     * and the last recorded clinical vitals. Null when neither exists
     * (patient not yet triaged).
     */
    private Instant lastAssessedAt(Visit visit) {
        TriageRecord lastTriage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visit.getId())
                .orElse(null);
        Instant triageTime = lastTriage != null ? lastTriage.getTriageTime() : visit.getTriageTime();

        Instant vitalsTime = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visit.getId())
                .map(VitalSigns::getRecordedAt)
                .orElse(null);

        if (triageTime == null) return vitalsTime;
        if (vitalsTime == null) return triageTime;
        return vitalsTime.isAfter(triageTime) ? vitalsTime : triageTime;
    }

    /**
     * Get all patients overdue for reassessment at a specific hospital.
     * Kept for the existing /retriage/overdue endpoint contract; now
     * derived from the same vitals-aware clock as the worklist.
     */
    @Transactional(readOnly = true)
    public List<OverduePatientResponse> getOverdueReassessments(UUID hospitalId) {
        List<OverduePatientResponse> overduePatients = new ArrayList<>();
        for (RecheckWorklistItem item : getRecheckWorklist(hospitalId, null)) {
            if (!item.isOverdue()) continue;
            long overdueBy = -item.getMinutesUntilDue();
            String severity =
                    (item.getCategory() == TriageCategory.RED
                            || (item.getIntervalMinutes() > 0 && overdueBy >= item.getIntervalMinutes()))
                            ? "CRITICAL" : "HIGH";
            overduePatients.add(OverduePatientResponse.builder()
                    .visitId(item.getVisitId())
                    .visitNumber(item.getVisitNumber())
                    .patientName(item.getPatientName())
                    .currentCategory(item.getCategory())
                    .tewsScore(item.getTewsScore())
                    .lastTriageTime(item.getLastAssessedAt())
                    .nextReassessmentDue(item.getNextDueAt())
                    .waitTimeMinutes(item.getIntervalMinutes() + overdueBy)
                    .maxWaitMinutes(item.getIntervalMinutes())
                    .overdueByMinutes(overdueBy)
                    .alertSeverity(severity)
                    .build());
        }
        return overduePatients;
    }
}
