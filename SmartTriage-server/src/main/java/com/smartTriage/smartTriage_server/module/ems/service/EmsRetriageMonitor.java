package com.smartTriage.smartTriage_server.module.ems.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
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
 * EmsRetriageMonitor — the EMS scheduled safety-net.
 *
 * Two independent silent-failure paths a paramedic-brought patient can
 * fall into once they reach the ED, each now both PERSISTED and PUSHED
 * LIVE (charge nurse + RESUS zone for RED) so it surfaces without anyone
 * happening to refresh the Alert Center:
 *
 *   1. {@link AlertType#FIELD_TRIAGED_AWAITING_REVIEW} — a paramedic
 *      called (e.g.) RED, the patient arrived, but no ED triage form has
 *      been filed within the re-triage window. {@link #checkRetriage()}.
 *
 *   2. {@link AlertType#EMS_HANDOVER_PENDING} — the patient is physically
 *      ARRIVED at the door but the receiving nurse never completed
 *      transfer-of-care, so the patient sits clinically unowned. This
 *      net was half-built (enum + query existed) but had no scheduler —
 *      {@link #checkHandoverPending()} now fires it.
 *
 * Both self-clear: re-triage clears when a TriageRecord is filed; the
 * handover net stops firing once the run leaves ARRIVED (transfer-of-care
 * flips it to HANDED_OFF). Dedup prevents re-raising while unacknowledged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmsRetriageMonitor {

    /** Minutes a patient may sit ARRIVED-at-door before the handover net fires. */
    private static final long HANDOVER_PENDING_MINUTES = 10;

    /** Minutes PAST the re-triage deadline (≈10 min after a RED arrival — the RED fuse is 5 min)
     *  at which the one-shot HIGH nudge RATCHETS to a CRITICAL, doctor-paging escalation for a
     *  field-RED patient the ED still hasn't triaged. Turns a nudge into an escalating loop. */
    private static final long RED_RETRIAGE_ESCALATION_AFTER_MINUTES = 5;
    /** The RED re-triage fuse (matches EmsRunService.ED_RETRIAGE_WINDOW_RED) — used only to
     *  estimate "minutes since arrival" for the escalation message when arrivalConfirmedAt is absent. */
    private static final long ED_RETRIAGE_WINDOW_RED_FALLBACK_MINUTES = 5;

    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final EmsRunRepository emsRunRepository;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final ShiftAssignmentService shiftAssignmentService;

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void checkRetriage() {
        Instant now = Instant.now();
        List<Visit> due = visitRepository.findRetriageDueBefore(now);
        for (Visit v : due) {
            try {
                boolean alreadyTriaged = triageRecordRepository
                        .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(v.getId())
                        .isPresent();
                if (alreadyTriaged) {
                    // Clear the deadline so we never re-scan this row.
                    v.setEdRetriageDueAt(null);
                    visitRepository.save(v);
                    continue;
                }

                long minutesOver = Duration.between(v.getEdRetriageDueAt(), now).toMinutes();
                boolean red = isRed(v.getFieldTriageCategory());
                boolean uncategorized = v.getFieldTriageCategory() == null
                        || String.valueOf(v.getFieldTriageCategory()).isBlank();
                // Both a field-RED and an UNCATEGORISED (colour-less) arrival are time-critical
                // unknowns that must escalate to a doctor if the ED never triages them; a
                // lower-acuity field call (YELLOW/GREEN/BLUE) gets the one HIGH nudge only.
                boolean escalatable = red || uncategorized;
                UUID hospitalId = v.getHospital() != null ? v.getHospital().getId() : null;

                // Look up the most-recent active alert REGARDLESS of acknowledgement. Using the
                // unacknowledged-only query here let a charge nurse silence the escalation by
                // acking (without triaging): the acked row looked like "no alert", so the next
                // tick re-minted a fresh HIGH tier-1 and the CRITICAL ratchet never fired.
                java.util.Optional<ClinicalAlert> existing = clinicalAlertRepository
                        .findFirstByVisitIdAndAlertTypeAndIsActiveTrueOrderByCreatedAtDesc(
                                v.getId(), AlertType.FIELD_TRIAGED_AWAITING_REVIEW);
                if (existing.isEmpty()) {
                    // First nudge — HIGH, tier 1.
                    ClinicalAlert alert = ClinicalAlert.builder()
                            .visit(v)
                            .alertType(AlertType.FIELD_TRIAGED_AWAITING_REVIEW)
                            .severity(AlertSeverity.HIGH)
                            .title("Re-triage overdue: " + safeTriageLabel(v))
                            .message(String.format(
                                    "Paramedic-brought patient (visit %s, field triage %s) has not been re-triaged " +
                                            "by the ED — overdue by %d min. Confirm the field call still holds.",
                                    v.getVisitNumber(),
                                    v.getFieldTriageCategory() != null ? v.getFieldTriageCategory() : "uncategorised",
                                    minutesOver))
                            // RED field calls route to the RESUS board; others fall back to
                            // the hospital-wide board (not yet zoned).
                            .targetZone(red ? EdZone.RESUS : null)
                            .escalationTier(1)
                            .autoGenerated(true)
                            .build();
                    alert = clinicalAlertRepository.save(alert);
                    publishLive(alert, hospitalId, red ? EdZone.RESUS : null);
                    log.warn("[ems] FIELD_TRIAGED_AWAITING_REVIEW raised + pushed for visit {} ({} min overdue)",
                            v.getVisitNumber(), minutesOver);
                } else if (escalatable
                        && existing.get().getEscalationTier() < 2
                        && minutesOver >= RED_RETRIAGE_ESCALATION_AFTER_MINUTES) {
                    // RATCHET: a RED / uncategorised patient is STILL un-triaged well past the
                    // deadline. Bump the alert to CRITICAL / tier-2, route to RESUS (reaches the
                    // resus doctor), and FORCE it back to unacknowledged even if it was acked —
                    // acknowledging must NOT be able to silence the escalation without an actual
                    // triage being filed. The tier+severity bump re-triggers the audible re-alarm.
                    ClinicalAlert a = existing.get();
                    long sinceArrival = v.getArrivalConfirmedAt() != null
                            ? Duration.between(v.getArrivalConfirmedAt(), now).toMinutes()
                            : minutesOver + ED_RETRIAGE_WINDOW_RED_FALLBACK_MINUTES;
                    a.setAcknowledged(false);
                    a.setAcknowledgedAt(null);
                    a.setAcknowledgedBy(null);
                    a.setSeverity(AlertSeverity.CRITICAL);
                    a.setEscalationTier(2);
                    a.setTitle("[ESCALATED] Re-triage OVERDUE: " + safeTriageLabel(v));
                    a.setMessage(String.format(
                            "%s (visit %s) is STILL not triaged ~%d min after arrival — escalating to " +
                                    "CRITICAL and paging the Resus doctor. Triage NOW.",
                            red ? "Field-RED patient" : "Uncategorised ambulance patient",
                            v.getVisitNumber(), sinceArrival));
                    a = clinicalAlertRepository.save(a);
                    publishLive(a, hospitalId, EdZone.RESUS);
                    log.error("[ems] FIELD_TRIAGED_AWAITING_REVIEW ESCALATED → CRITICAL for visit {} " +
                            "({} min past due) — paging Resus doctor", v.getVisitNumber(), minutesOver);
                }
            } catch (Exception e) {
                log.error("[ems] retriage monitor error for visit {}: {}", v.getId(), e.getMessage());
            }
        }
    }

    /**
     * Fire EMS_HANDOVER_PENDING for any patient still ARRIVED at the door
     * past the threshold with no transfer-of-care ack — the previously-dead
     * half of the safety net.
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void checkHandoverPending() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(HANDOVER_PENDING_MINUTES));
        List<EmsRun> stuck = emsRunRepository.findArrivedAwaitingHandoverBefore(cutoff);
        for (EmsRun run : stuck) {
            try {
                Visit v = run.getVisit();
                if (v == null) {
                    // ARRIVED implies a pre-registered visit; if absent there is nothing to
                    // anchor the alert to — log and skip rather than raise an orphan.
                    log.warn("[ems] EMS_HANDOVER_PENDING skip — ARRIVED run {} has no linked visit", run.getId());
                    continue;
                }
                boolean alertExists = clinicalAlertRepository
                        .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                                v.getId(), AlertType.EMS_HANDOVER_PENDING);
                if (alertExists) continue;

                long waiting = run.getEdArrivedAt() != null
                        ? Duration.between(run.getEdArrivedAt(), Instant.now()).toMinutes()
                        : HANDOVER_PENDING_MINUTES;
                boolean red = isRed(run.getFieldTriageCategory());
                UUID hospitalId = run.getHospital() != null ? run.getHospital().getId() : null;

                ClinicalAlert alert = ClinicalAlert.builder()
                        .visit(v)
                        .alertType(AlertType.EMS_HANDOVER_PENDING)
                        .severity(AlertSeverity.HIGH)
                        .title("Transfer of care pending: " + safeRunLabel(run))
                        .message(String.format(
                                "Ambulance patient (visit %s, field triage %s) has been AT THE ED DOOR for %d min "
                                        + "with no transfer-of-care acknowledgement. A receiving nurse must take "
                                        + "handover — the patient is currently clinically unowned.",
                                v.getVisitNumber(),
                                run.getFieldTriageCategory() != null ? run.getFieldTriageCategory() : "?",
                                waiting))
                        .targetZone(red ? EdZone.RESUS : null)
                        .escalationTier(1)
                        .autoGenerated(true)
                        .build();
                alert = clinicalAlertRepository.save(alert);
                publishLive(alert, hospitalId, red ? EdZone.RESUS : null);
                log.warn("[ems] EMS_HANDOVER_PENDING raised + pushed for visit {} (run {}, {} min at door)",
                        v.getVisitNumber(), run.getId(), waiting);
            } catch (Exception e) {
                log.error("[ems] handover-pending monitor error for run {}: {}", run.getId(), e.getMessage());
            }
        }
    }

    /**
     * Fan the saved alert out to the receiving ED AFTER COMMIT (charge nurse
     * + RESUS zone for RED), mirroring EmsRunService.routePreArrivalAlert so a
     * rolled-back scheduler tick never pushes a phantom alert.
     */
    private void publishLive(ClinicalAlert alert, UUID hospitalId, EdZone targetZone) {
        if (hospitalId == null) {
            log.warn("[ems] cannot push alert {} live — no hospital resolved", alert.getId());
            return;
        }
        try {
            ClinicalAlertResponse resp = ClinicalAlertMapper.toResponse(alert);
            List<User> chargeNurses = shiftAssignmentService.getChargeNurse(hospitalId);
            List<UUID> userIds = chargeNurses.stream()
                    .filter(cn -> cn != null && cn.getId() != null)
                    .map(User::getId)
                    .toList();
            realTimeEventPublisher.publishOwnedAlertAfterCommit(hospitalId, targetZone, resp, userIds);
        } catch (Exception e) {
            log.warn("[ems] live publish failed for alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    private static boolean isRed(Object fieldTriageCategory) {
        return fieldTriageCategory != null && "RED".equals(String.valueOf(fieldTriageCategory));
    }

    private static String safeTriageLabel(Visit v) {
        return v.getFieldTriageCategory() != null
                ? "field " + v.getFieldTriageCategory()
                : "EMS arrival";
    }

    private static String safeRunLabel(EmsRun run) {
        return run.getMechanism() != null && !run.getMechanism().isBlank()
                ? run.getMechanism()
                : "EMS arrival";
    }
}
