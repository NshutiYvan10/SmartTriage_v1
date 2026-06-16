package com.smartTriage.smartTriage_server.module.fasttrack.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import com.smartTriage.smartTriage_server.common.enums.VisitStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;
import com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository;
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
import java.util.Map;
import java.util.UUID;

/**
 * FastTrackMonitorService — re-escalates Fast Track door-to-target SLA breaches.
 *
 * The fast-track audit found that door-to-ECG (&lt;10 min) and door-to-CT
 * (&lt;25 min) misses only emitted a server-side log.warn — the timing-governance
 * value never reached the clinicians who could act. This scheduled monitor scans
 * active (non-terminal) activations and, on a breach, raises a CRITICAL
 * FAST_TRACK_SLA_BREACH owned by the zone doctor + charge nurse and pushed in
 * real time. Dedups on the DISTINCT breach type so the original
 * FAST_TRACK_ACTIVATED alert can't suppress it (the sepsis-monitor lesson).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FastTrackMonitorService {

    /** Rwanda-adapted door-to-target minutes. */
    private static final long DOOR_TO_ECG_TARGET_MIN = 10;
    private static final long DOOR_TO_CT_TARGET_MIN = 25;
    private static final long DOOR_TO_TREATMENT_TARGET_MIN = 60;

    private static final List<FastTrackStatus> TERMINAL =
            List.of(FastTrackStatus.COMPLETED, FastTrackStatus.CANCELLED);

    /** Visit statuses meaning the patient has left the ED — disposition does NOT
     *  auto-close the activation, so we must skip these to avoid perpetually
     *  re-escalating a discharged / admitted / transferred / deceased patient. */
    private static final java.util.Set<VisitStatus> TERMINAL_VISIT = java.util.EnumSet.of(
            VisitStatus.DISCHARGED, VisitStatus.ADMITTED, VisitStatus.ICU_ADMITTED,
            VisitStatus.TRANSFERRED, VisitStatus.LEFT_WITHOUT_BEING_SEEN, VisitStatus.DECEASED);

    private final FastTrackActivationRepository fastTrackActivationRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    @Scheduled(fixedDelayString = "${smarttriage.fasttrack.monitor-interval-ms:60000}")
    @Transactional
    public int checkSlaBreaches() {
        List<FastTrackActivation> active = fastTrackActivationRepository.findByStatusNotInAndIsActiveTrue(TERMINAL);
        Instant now = Instant.now();
        int raised = 0;

        for (FastTrackActivation a : active) {
            Visit visit = a.getVisit();
            if (visit == null || visit.getArrivalTime() == null) continue;
            // Patient has left the ED — skip (no perpetual re-escalation).
            if (visit.getStatus() != null && TERMINAL_VISIT.contains(visit.getStatus())) continue;
            long mins = Duration.between(visit.getArrivalTime(), now).toMinutes();

            String breach = detectBreach(a, mins);
            if (breach == null) continue;

            UUID visitId = visit.getId();
            // Dedup on the DISTINCT breach type — an unacknowledged
            // FAST_TRACK_ACTIVATED alert must not suppress this.
            if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                    visitId, AlertType.FAST_TRACK_SLA_BREACH)) {
                continue;
            }

            raiseEscalation(a, visit, breach);
            raised++;
            log.error("FAST-TRACK SLA BREACH: visit {} | {}", visit.getVisitNumber(), breach);
        }

        if (raised > 0) {
            log.info("Fast-track monitor: raised {} SLA-breach escalation(s)", raised);
        }
        return raised;
    }

    /** Returns a breach description, or null if within targets. */
    private String detectBreach(FastTrackActivation a, long minsSinceArrival) {
        FastTrackType type = a.getFastTrackType();
        boolean stroke = type == FastTrackType.STROKE_SUSPECTED || type == FastTrackType.TIA_SUSPECTED;

        if (stroke) {
            if (a.getCtCompletedAt() == null && minsSinceArrival > DOOR_TO_CT_TARGET_MIN) {
                return String.format("door-to-CT target missed — CT not completed %d min after arrival (target < %d min)",
                        minsSinceArrival, DOOR_TO_CT_TARGET_MIN);
            }
        } else {
            if (a.getEcgCompletedAt() == null && minsSinceArrival > DOOR_TO_ECG_TARGET_MIN) {
                return String.format("door-to-ECG target missed — ECG not completed %d min after arrival (target < %d min)",
                        minsSinceArrival, DOOR_TO_ECG_TARGET_MIN);
            }
        }
        // Door-to-treatment: no thrombolysis / PCI referral within 60 min.
        // Excludes (a) HEMORRHAGIC stroke — thrombolysis/PCI is CONTRAINDICATED so
        // those timestamps are correctly never set and nagging would be clinically
        // wrong; (b) an activation a clinician has explicitly ACKNOWLEDGED (they
        // own the clock — re-paging would be alarm fatigue).
        if (a.getThrombolysisStartedAt() == null && a.getReferredForPciAt() == null
                && !Boolean.TRUE.equals(a.getIsHemorrhagic())
                && a.getAcknowledgedAt() == null
                && minsSinceArrival > DOOR_TO_TREATMENT_TARGET_MIN) {
            return String.format("door-to-treatment target missed — no intervention started %d min after arrival (target < %d min)",
                    minsSinceArrival, DOOR_TO_TREATMENT_TARGET_MIN);
        }
        // NOTE: this is a CURRENT-STATE monitor — it escalates not-yet-done
        // breaches. A LATE completion (e.g. CT done at 40 min) is not re-escalated
        // here; that timing miss is captured by the quality-metrics path. Dedup
        // collapses to one unacknowledged breach per visit (anti-spam), so only
        // the first breach reason shows until acknowledged.
        return null;
    }

    private void raiseEscalation(FastTrackActivation a, Visit visit, String breach) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = null;
        if (hospitalId != null && zone != null) {
            List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
            if (!doctors.isEmpty()) zoneDoctor = doctors.get(0);
        }
        String patientName = visit.getPatient() != null
                ? (visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName())
                : "patient";

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.FAST_TRACK_SLA_BREACH)
                .severity(AlertSeverity.CRITICAL)
                .title("FAST-TRACK SLA BREACH — " + a.getFastTrackType().name())
                .message(String.format(
                        "Time-critical %s pathway for %s (Visit: %s): %s. IMMEDIATE ACTION REQUIRED.",
                        a.getFastTrackType().name(), patientName, visit.getVisitNumber(), breach))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(2)
                .autoGenerated(true)
                .build();
        alert = clinicalAlertRepository.save(alert);

        try {
            if (hospitalId != null) {
                var resp = ClinicalAlertMapper.toResponse(alert);
                realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
                if (zone != null) realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
                if (zoneDoctor != null) realTimeEventPublisher.publishUserAlert(zoneDoctor.getId(), resp);
                for (User cn : shiftAssignmentService.getChargeNurse(hospitalId)) {
                    realTimeEventPublisher.publishUserAlert(cn.getId(), resp);
                }
                realTimeEventPublisher.publishFastTrackEventAfterCommit(hospitalId, Map.of(
                        "eventType", "SLA_BREACH",
                        "visitId", visit.getId().toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to publish fast-track SLA breach for visit {}: {}", visit.getId(), e.getMessage());
        }
    }
}
