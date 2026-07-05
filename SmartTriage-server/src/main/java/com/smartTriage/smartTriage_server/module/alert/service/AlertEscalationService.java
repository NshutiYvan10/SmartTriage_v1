package com.smartTriage.smartTriage_server.module.alert.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Alert Escalation Service — implements the SmartTriage tiered notification
 * system.
 *
 * When a patient is triaged RED or ORANGE:
 * Tier 1 (instant): Route to zone doctor + charge nurse — "Dr. X, new ORANGE in
 * ACUTE bay 3"
 * Tier 2 (+2 min unacknowledged): Broadcast to ALL on-duty doctors —
 * "Unacknowledged ORANGE"
 * Tier 3 (+5 min unacknowledged): Alert EVERYONE on shift + flag audible alarm
 *
 * SATS target times:
 * RED → 0 min (immediate)
 * ORANGE → 10 min (doctor MUST see within 10 min)
 * YELLOW → 30 min
 * GREEN → 60 min
 *
 * The escalation scheduler runs every 30 seconds checking for unacknowledged
 * alerts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEscalationService {

    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher eventPublisher;

    // Escalation thresholds
    private static final int TIER_2_MINUTES = 2;
    private static final int TIER_3_MINUTES = 5;
    /** A CRITICAL ambulance pre-arrival (RED / lights) unacknowledged this long is re-alarmed. */
    private static final int EMS_PREARRIVAL_REESCALATE_MINUTES = 2;
    /**
     * Once an alert has reached the top of its escalation path, an unacknowledged
     * instance is RE-PAGED AGAIN every this-many minutes — a life-critical alert must
     * NOT fall silent after a single top-tier page. Applies to the doctor Tier-3 loop
     * and the generic time-critical re-broadcast loop. The re-page keeps widening/
     * re-alarming until a human acknowledges (which, for CRITICAL alerts, now requires a
     * documented reason — see ClinicalAlertService.acknowledgeAlert) or the alert/visit
     * is closed (isActive=false), at which point the finders stop returning it.
     */
    private static final int TIER_REPEAT_MINUTES = 5;

    /**
     * Create and route a doctor notification alert for a triaged patient.
     * Called by TriageService when a patient is triaged RED/ORANGE.
     */
    @Transactional
    public ClinicalAlert createZoneRoutedAlert(Visit visit, TriageCategory category, int tewsScore,
            String decisionPath) {
        UUID hospitalId = visit.getPatient().getHospital().getId();
        EdZone targetZone = EdZone.fromTriageCategory(category);

        // Determine SATS target minutes
        int satsMinutes = getSatsTargetMinutes(category);

        // Find zone doctor(s)
        List<User> zoneDoctors = shiftAssignmentService.getDoctorsForZone(hospitalId, targetZone);
        User targetDoctor = zoneDoctors.isEmpty() ? null : zoneDoctors.get(0);

        AlertSeverity severity = category == TriageCategory.RED ? AlertSeverity.CRITICAL : AlertSeverity.HIGH;
        AlertType alertType = AlertType.DOCTOR_NOTIFICATION;

        String patientName = visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
        String doctorLabel = targetDoctor != null
                ? "Dr. " + targetDoctor.getLastName()
                : "No doctor assigned to " + targetZone.name();

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(alertType)
                .severity(severity)
                .title(String.format("%s Triage — %s", category.name(), visit.getVisitNumber()))
                .message(String.format(
                        "%s, new %s patient %s in %s zone (TEWS: %d). %s. SATS target: %d min.",
                        doctorLabel, category.name(), patientName, targetZone.name(),
                        tewsScore, decisionPath, satsMinutes))
                .targetZone(targetZone)
                .escalationTier(1)
                .escalatedAt(Instant.now())
                .targetDoctor(targetDoctor)
                .satsTargetMinutes(satsMinutes)
                .autoGenerated(true)
                .build();

        alert = clinicalAlertRepository.save(alert);
        log.info("TIER 1 ALERT: {} patient {} → {} zone → {} | Alert ID: {}",
                category, patientName, targetZone, doctorLabel, alert.getId());

        // The alert row is now persisted. Everything below (WS pushes, shift lookups,
        // the no-doctor auto-Tier-2) is BEST-EFFORT — wrapped so a broker hiccup
        // (MessagingException) or a shift-lookup DB blip can NEVER propagate out of this
        // method. If it did, the caller (TriageService.performTriage) would catch it and run
        // its fallback, which would persist a SECOND DOCTOR_NOTIFICATION for the same patient
        // (a duplicate page) — and a propagated exception would also roll back the entire
        // triage transaction. A dropped live push is harmless: the @Scheduled escalation loop
        // re-finds the unacknowledged Tier-1 alert and escalates it within ~2 min.
        try {
            ClinicalAlertResponse response = ClinicalAlertMapper.toResponse(alert);

            // Send to zone topic (zone doctor + nurses in that zone)
            eventPublisher.publishZoneAlert(hospitalId, targetZone, response);

            // Also send to hospital-wide alert topic (all dashboards)
            eventPublisher.publishHospitalAlert(hospitalId, response);

            // Notify the target zone doctor directly via user-targeted topic
            if (targetDoctor != null) {
                eventPublisher.publishUserAlert(targetDoctor.getId(), response);
            }

            // Notify the charge nurse on duty via user-targeted topic (Tier 1 recipient)
            List<User> chargeNurses = shiftAssignmentService.getChargeNurse(hospitalId);
            for (User cn : chargeNurses) {
                eventPublisher.publishUserAlert(cn.getId(), response);
            }

            // If zone has no doctor assigned, immediately escalate to Tier 2
            if (targetDoctor == null) {
                log.warn("No doctor assigned to {} zone — auto-escalating to Tier 2", targetZone);
                escalateToTier2(alert, hospitalId);
            }
        } catch (Exception e) {
            log.error("TIER 1 alert {} saved but live routing failed ({}). The scheduled "
                    + "escalation loop will re-page it; not propagating to avoid a duplicate "
                    + "fallback alert / triage rollback.", alert.getId(), e.getMessage());
        }

        return alert;
    }

    /**
     * Scheduled escalation check — runs every 30 seconds.
     * Finds unacknowledged alerts and escalates them through tiers.
     */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void checkEscalations() {
        // Find all unacknowledged DOCTOR_NOTIFICATION alerts
        List<ClinicalAlert> unacknowledged = clinicalAlertRepository
                .findUnacknowledgedDoctorNotifications();

        for (ClinicalAlert alert : unacknowledged) {
            if (alert.getEscalatedAt() == null)
                continue;

            long minutesSinceEscalation = ChronoUnit.MINUTES.between(alert.getEscalatedAt(), Instant.now());
            UUID hospitalId = alert.getVisit().getPatient().getHospital().getId();
            int tier = alert.getEscalationTier();

            if (tier == 1 && minutesSinceEscalation >= TIER_2_MINUTES) {
                escalateToTier2(alert, hospitalId);
            } else if (tier == 2 && minutesSinceEscalation >= TIER_3_MINUTES) {
                escalateToTier3(alert, hospitalId);
            } else if (tier >= 3 && minutesSinceEscalation >= TIER_REPEAT_MINUTES) {
                // NO DEAD-END: an unacknowledged doctor alert that already reached the top
                // tier keeps re-paging all-staff + audible every TIER_REPEAT_MINUTES until it
                // is acknowledged. escalateToTier3 increments the tier so the client — which
                // re-alarms on a tier INCREASE — beeps again each cycle.
                escalateToTier3(alert, hospitalId);
            }
        }

        // Time-critical clinical alerts (sepsis, ICU escalation, critical lab unack,
        // deterioration, hypoglycemia, missed dose, fast-track, …) — separate pipeline
        // because they don't share DOCTOR_NOTIFICATION's tier semantics, but they share the
        // need for "if nobody ack'd this, re-broadcast to everyone."
        //
        // NO DEAD-END (patient-safety fix): the first re-page fires once the ack window
        // (TIER_3_MINUTES from creation) elapses, and then the alert is RE-PAGED AGAIN every
        // TIER_REPEAT_MINUTES for as long as it stays unacknowledged. Previously this bumped
        // exactly once (escalatedAt != null → skip forever), so a life-critical alert nobody
        // acknowledged fell permanently silent after a single beep. rebroadcastTimeCriticalAlert
        // increments the tier each cycle so the client re-alarms; escalatedAt is the
        // "last paged" clock, and the finder still excludes acknowledged/inactive alerts.
        for (ClinicalAlert alert : clinicalAlertRepository
                .findUnacknowledgedTimeCriticalAlerts(AlertType.timeCriticalTypes())) {
            try {
                Instant reference = alert.getEscalatedAt() != null
                        ? alert.getEscalatedAt()
                        : (alert.getCreatedAt() != null ? alert.getCreatedAt() : Instant.now());
                long minutesSinceReference = ChronoUnit.MINUTES.between(reference, Instant.now());
                // First page: TIER_3_MINUTES after creation. Subsequent re-pages: every
                // TIER_REPEAT_MINUTES after the previous page (escalatedAt).
                long threshold = alert.getEscalatedAt() == null ? TIER_3_MINUTES : TIER_REPEAT_MINUTES;
                if (minutesSinceReference < threshold) {
                    continue; // still inside the current ack window
                }
                UUID hospitalId = alert.getVisit().getPatient().getHospital().getId();
                rebroadcastTimeCriticalAlert(alert, hospitalId);
            } catch (Exception e) {
                log.warn("Failed to escalate time-critical alert {}: {}",
                        alert.getId(), e.getMessage());
            }
        }

        // Incoming CRITICAL ambulance pre-arrivals (RED / lights). These fire once on submit;
        // if the receiving team doesn't acknowledge a crashing patient who may be only minutes
        // out, re-alarm hospital-wide on a SHORT fuse (the ETA can be < 5 min). NO DEAD-END:
        // re-paged every EMS_PREARRIVAL_REESCALATE_MINUTES until acknowledged, not just once.
        for (ClinicalAlert alert : clinicalAlertRepository.findUnacknowledgedCriticalEmsPreArrivals()) {
            try {
                Instant reference = alert.getEscalatedAt() != null
                        ? alert.getEscalatedAt()
                        : (alert.getCreatedAt() != null ? alert.getCreatedAt() : Instant.now());
                if (ChronoUnit.MINUTES.between(reference, Instant.now()) < EMS_PREARRIVAL_REESCALATE_MINUTES) {
                    continue; // still inside the current ack window
                }
                UUID hospitalId = alert.getVisit().getPatient().getHospital().getId();
                rebroadcastTimeCriticalAlert(alert, hospitalId);
                log.warn("[escalation] Unacknowledged CRITICAL inbound pre-arrival {} re-alarmed at hospital {}",
                        alert.getId(), hospitalId);
            } catch (Exception e) {
                log.warn("Failed to escalate inbound pre-arrival alert {}: {}",
                        alert.getId(), e.getMessage());
            }
        }
    }

    /**
     * Mark a time-critical alert as escalated and re-publish it
     * hospital-wide with audible-alarm + escalationTier=2 so connected
     * clients beep + flash again. Tier 3 isn't applicable here — these
     * alert types don't have the manual ack-by-doctor flow that
     * DOCTOR_NOTIFICATION does; the second broadcast IS the escalation.
     */
    private void rebroadcastTimeCriticalAlert(ClinicalAlert alert, UUID hospitalId) {
        long unackedMin = alert.getCreatedAt() != null
                ? ChronoUnit.MINUTES.between(alert.getCreatedAt(), Instant.now())
                : 0;
        alert.setEscalatedAt(Instant.now());
        // Bump the tier on EVERY re-page so the client recognises a NEW escalation event (the
        // notifier keys on id+escalationTier and re-alarms on an increase). This loop now runs
        // repeatedly for an unacknowledged alert (escalatedAt is the "last paged" clock, not a
        // one-shot latch), so the tier climbs 2, 3, 4, … each cycle and the client beeps again.
        alert.setEscalationTier(alert.getEscalationTier() + 1);
        // Escalation RAISES urgency: an unacknowledged time-critical alert nobody has
        // touched becomes CRITICAL so it triggers the audible re-alarm. (Lower-acuity
        // turnaround types like ROUTINE/URGENT_LAB_OVERDUE are deliberately NOT in the
        // time-critical set, so this never over-promotes a routine event.)
        alert.setSeverity(AlertSeverity.CRITICAL);
        // Refresh the escalation suffix each cycle so the "unacknowledged for N min" count is
        // current (strip any previous suffix first rather than appending repeatedly).
        String base = alert.getMessage() != null ? alert.getMessage() : "";
        int marker = base.indexOf("  [ESCALATED");
        if (marker >= 0) {
            base = base.substring(0, marker);
        }
        alert.setMessage(base + "  [ESCALATED — unacknowledged for " + unackedMin + " min]");
        clinicalAlertRepository.save(alert);

        // Publish the TYPED ClinicalAlertResponse so every client parses it identically to
        // any other alert (id / alertType / severity / category / escalationTier present).
        // The previous nested {alert, audibleAlarm} Map was unparseable by the client's
        // mapWsAlert — it degraded to a generic non-critical alert with no id, so the
        // re-page never re-alarmed and never deduped. The bumped tier on this typed payload
        // is what the client notifier keys on to re-alarm.
        eventPublisher.publishHospitalAlert(hospitalId, ClinicalAlertMapper.toResponse(alert));

        log.warn("[escalation] {} alert {} escalated (tier {}) — unacked for {} min, hospital {}",
                alert.getAlertType(), alert.getId(), alert.getEscalationTier(), unackedMin, hospitalId);
    }

    /**
     * Tier 2 escalation — broadcast to ALL on-duty doctors.
     */
    private void escalateToTier2(ClinicalAlert alert, UUID hospitalId) {
        alert.setEscalationTier(2);
        alert.setEscalatedAt(Instant.now());
        alert.setAlertType(AlertType.DOCTOR_ESCALATION);
        alert.setSeverity(AlertSeverity.CRITICAL);
        alert = clinicalAlertRepository.save(alert);

        String patientName = alert.getVisit().getPatient().getFirstName() + " "
                + alert.getVisit().getPatient().getLastName();
        log.warn("TIER 2 ESCALATION: Alert {} for patient {} — broadcasting to ALL doctors",
                alert.getId(), patientName);

        ClinicalAlertResponse response = ClinicalAlertMapper.toResponse(alert);

        // Send to all on-duty doctors via user-targeted topics
        List<User> allDoctors = shiftAssignmentService.getAllDoctorsOnDuty(hospitalId);
        for (User doctor : allDoctors) {
            eventPublisher.publishUserAlert(doctor.getId(), response);
        }

        // Update hospital-wide alert
        eventPublisher.publishHospitalAlert(hospitalId, response);
    }

    /**
     * Tier 3 escalation — alert EVERYONE on shift + audible alarm flag.
     */
    private void escalateToTier3(ClinicalAlert alert, UUID hospitalId) {
        // First arrival at the top tier sets 3; each subsequent unacknowledged repeat
        // increments (4, 5, …) so the client notifier — which re-alarms on a tier INCREASE —
        // beeps again every cycle instead of falling silent after a single tier-3 page.
        int nextTier = alert.getEscalationTier() < 3 ? 3 : alert.getEscalationTier() + 1;
        alert.setEscalationTier(nextTier);
        alert.setEscalatedAt(Instant.now());
        alert.setSeverity(AlertSeverity.CRITICAL);
        alert = clinicalAlertRepository.save(alert);

        String patientName = alert.getVisit().getPatient().getFirstName() + " "
                + alert.getVisit().getPatient().getLastName();
        log.error("TIER {} ESCALATION: Alert {} for patient {} — alerting ALL STAFF + AUDIBLE ALARM",
                nextTier, alert.getId(), patientName);

        ClinicalAlertResponse response = ClinicalAlertMapper.toResponse(alert);

        // Broadcast to all zones
        for (EdZone zone : EdZone.values()) {
            eventPublisher.publishZoneAlert(hospitalId, zone, response);
        }

        // Send hospital-wide typed ClinicalAlertResponse. The tier-3 bump (+ CRITICAL
        // severity) on this payload is what the client notifier keys on to re-alarm — the
        // previous parallel `{audibleAlarm:true}` Map was never read by any client and is
        // dropped here to avoid sending an unparseable second payload.
        eventPublisher.publishHospitalAlert(hospitalId, response);
    }

    /**
     * Get SATS maximum wait time in minutes for a triage category.
     */
    public static int getSatsTargetMinutes(TriageCategory category) {
        return switch (category) {
            case RED -> 0;
            case ORANGE -> 10;
            case YELLOW -> 30;
            case GREEN -> 60;
            case BLUE -> 120;
        };
    }

}
