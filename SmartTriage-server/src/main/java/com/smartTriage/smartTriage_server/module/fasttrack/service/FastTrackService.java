package com.smartTriage.smartTriage_server.module.fasttrack.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.CtResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.EcgResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackActivationRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.engine.StrokeMIDetectionEngine;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;
import com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FastTrackService — manages stroke and MI fast-track protocol activations.
 *
 * Rwanda context targets (adapted for available resources):
 * - Door-to-ECG: < 10 minutes
 * - Door-to-CT: < 25 minutes
 * - Door-to-needle (thrombolysis): < 60 minutes
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FastTrackService {

    /** IV-tPA windows from symptom onset (minutes). */
    private static final long TPA_STANDARD_WINDOW_MIN = 180;   // 0–3 h
    private static final long TPA_EXTENDED_WINDOW_MIN = 270;    // 3–4.5 h

    private final FastTrackActivationRepository fastTrackActivationRepository;
    private final VisitRepository visitRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final RealTimeEventPublisher realTimeEventPublisher;
    private final ShiftAssignmentService shiftAssignmentService;
    private final TriageRecordRepository triageRecordRepository;
    private final StrokeMIDetectionEngine strokeMIDetectionEngine;

    /**
     * Activate a fast-track protocol for a visit.
     * Auto-orders ECG for MI, generates a CRITICAL alert owned by the zone doctor.
     */
    @Transactional
    public FastTrackActivation activateFastTrack(FastTrackActivationRequest request) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(request.getVisitId())
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", request.getVisitId()));

        // Block a duplicate activation of the SAME pathway family; allow a
        // distinct concurrent pathway (e.g. a patient who is both a stroke and
        // an MI concern) since blocking all would hide a second time-critical clock.
        List<FastTrackStatus> terminalStatuses = List.of(FastTrackStatus.COMPLETED, FastTrackStatus.CANCELLED);
        for (FastTrackActivation existing : fastTrackActivationRepository
                .findByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(request.getVisitId())) {
            if (!terminalStatuses.contains(existing.getStatus())
                    && sameFamily(existing.getFastTrackType(), request.getFastTrackType())) {
                log.warn("Fast-track {} already active for visit {}", existing.getFastTrackType(), request.getVisitId());
                throw new ClinicalBusinessException(
                        "A " + family(request.getFastTrackType()) + " fast-track is already active for this visit");
            }
        }

        Instant now = Instant.now();

        // Audit integrity: the activator is the authenticated user, NEVER a
        // client-supplied free-text name (spoofable). Falls back to the "System"
        // sentinel if no principal resolves (e.g. detached-principal lazy
        // failure) — request.getActivatedByName() is deliberately not honoured.
        String activatedBy = resolveCurrentUserName();
        if (activatedBy == null) activatedBy = "System";

        FastTrackActivation activation = FastTrackActivation.builder()
                .visit(visit)
                .fastTrackType(request.getFastTrackType())
                .status(FastTrackStatus.ACTIVATED)
                .activatedAt(now)
                .activatedByName(activatedBy)
                .symptomOnsetTime(request.getSymptomOnsetTime())
                .beFastScore(request.getBeFastScore())
                .nihssScore(request.getNihssScore())
                .chestPainOnsetTime(request.getChestPainOnsetTime())
                .notes(request.getNotes())
                .build();

        // Auto-order ECG for MI fast-tracks
        if (request.getFastTrackType() == FastTrackType.STEMI_SUSPECTED
                || request.getFastTrackType() == FastTrackType.NSTEMI_SUSPECTED) {
            activation.setEcgOrderedAt(now);
            activation.setStatus(FastTrackStatus.ECG_ORDERED);
            log.info("ECG auto-ordered for MI fast-track, visit {}", visit.getId());
        }

        activation = fastTrackActivationRepository.save(activation);

        // Owned, real-time CRITICAL alert (zone doctor + charge nurse).
        generateFastTrackAlert(visit, activation);

        // Live dashboard / panel refresh.
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        if (hospitalId != null) {
            realTimeEventPublisher.publishFastTrackEventAfterCommit(hospitalId, Map.of(
                    "eventType", "ACTIVATED",
                    "visitId", visit.getId().toString(),
                    "fastTrackType", activation.getFastTrackType().name()));
        }

        log.warn("Fast-track activated: type={}, visit={}, id={}, by={}",
                activation.getFastTrackType(), visit.getId(), activation.getId(), activatedBy);

        return activation;
    }

    /**
     * Update the status of a fast-track activation.
     * Computes door-to-X times when relevant status transitions occur.
     */
    @Transactional
    public FastTrackActivation updateStatus(UUID activationId, FastTrackStatus newStatus) {
        FastTrackActivation activation = loadModifiable(activationId);

        activation.setStatus(newStatus);
        activation.setLastUpdatedByName(resolveCurrentUserName());

        Instant now = Instant.now();

        switch (newStatus) {
            case ECG_ORDERED -> activation.setEcgOrderedAt(now);
            case CT_ORDERED -> activation.setCtOrderedAt(now);
            case INTERVENTION_STARTED -> activation.setThrombolysisStartedAt(now);
            case TRANSFERRED_FOR_PCI -> {
                activation.setReferredForPci(true);
                activation.setReferredForPciAt(now);
            }
            case COMPLETED -> {
                activation.setCompletedAt(now);
                activation.setCompletedByName(resolveCurrentUserName());
                computeDoorToNeedleMinutes(activation);
            }
            case CANCELLED -> {
                activation.setCompletedAt(now);
                activation.setCompletedByName(resolveCurrentUserName());
            }
            default -> { /* no additional action */ }
        }

        activation = fastTrackActivationRepository.save(activation);
        publishDashboardEvent(activation, "STATUS_" + newStatus.name());

        log.info("Fast-track status updated: id={}, newStatus={}", activationId, newStatus);
        return activation;
    }

    /** Complete a fast-track with an outcome note (sets the actor + door-to-needle). */
    @Transactional
    public FastTrackActivation complete(UUID activationId, String outcome) {
        FastTrackActivation activation = loadModifiable(activationId);
        Instant now = Instant.now();
        activation.setStatus(FastTrackStatus.COMPLETED);
        activation.setCompletedAt(now);
        activation.setCompletedByName(resolveCurrentUserName());
        activation.setLastUpdatedByName(resolveCurrentUserName());
        if (outcome != null && !outcome.isBlank()) activation.setOutcome(outcome);
        computeDoorToNeedleMinutes(activation);
        activation = fastTrackActivationRepository.save(activation);
        publishDashboardEvent(activation, "COMPLETED");
        log.info("Fast-track completed: id={}, by={}", activationId, activation.getCompletedByName());
        return activation;
    }

    /** Cancel a fast-track (e.g. activated in error, or ruled out). */
    @Transactional
    public FastTrackActivation cancel(UUID activationId, String reason) {
        FastTrackActivation activation = loadModifiable(activationId);
        Instant now = Instant.now();
        activation.setStatus(FastTrackStatus.CANCELLED);
        activation.setCompletedAt(now);
        activation.setCompletedByName(resolveCurrentUserName());
        activation.setLastUpdatedByName(resolveCurrentUserName());
        String stamp = "Cancelled" + (reason != null && !reason.isBlank() ? ": " + reason : "");
        activation.setOutcome(activation.getOutcome() == null ? stamp : activation.getOutcome() + " | " + stamp);
        activation = fastTrackActivationRepository.save(activation);
        publishDashboardEvent(activation, "CANCELLED");
        log.info("Fast-track cancelled: id={}, by={}", activationId, activation.getCompletedByName());
        return activation;
    }

    /** A clinician explicitly accepts ownership of the door-to-treatment clock. */
    @Transactional
    public FastTrackActivation acknowledge(UUID activationId) {
        FastTrackActivation activation = loadModifiable(activationId);
        if (activation.getAcknowledgedAt() == null) {
            activation.setAcknowledgedAt(Instant.now());
            activation.setAcknowledgedByName(resolveCurrentUserName());
            activation = fastTrackActivationRepository.save(activation);
            // Accepting the clock must genuinely stop the time-critical
            // re-broadcast loop — acknowledge the open FAST_TRACK_ACTIVATED alert,
            // not just the activation row (else it keeps re-paging hospital-wide).
            acknowledgeOpenActivationAlert(activation);
            publishDashboardEvent(activation, "ACKNOWLEDGED");
            log.info("Fast-track acknowledged: id={}, by={}", activationId, activation.getAcknowledgedByName());
        }
        return activation;
    }

    /** Acknowledge the open FAST_TRACK_ACTIVATED alert for this visit so it stops
     *  re-escalating once a clinician has accepted the door-to-treatment clock. */
    private void acknowledgeOpenActivationAlert(FastTrackActivation activation) {
        try {
            UUID visitId = activation.getVisit() != null ? activation.getVisit().getId() : null;
            if (visitId == null) return;
            clinicalAlertRepository
                    .findFirstByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            visitId, AlertType.FAST_TRACK_ACTIVATED)
                    .ifPresent(alert -> {
                        alert.setAcknowledged(true);
                        alert.setAcknowledgedAt(Instant.now());
                        clinicalAlertRepository.save(alert);
                    });
        } catch (Exception e) {
            log.warn("Failed to acknowledge fast-track activation alert: {}", e.getMessage());
        }
    }

    /**
     * Record ECG result for a fast-track activation.
     * Updates status and computes door-to-ECG time.
     */
    @Transactional
    public FastTrackActivation recordEcg(UUID activationId, EcgResultRequest request) {
        FastTrackActivation activation = loadModifiable(activationId);

        Instant now = Instant.now();
        activation.setEcgCompletedAt(now);
        activation.setEcgResult(request.getEcgResult());
        activation.setStElevation(request.getStElevation());
        activation.setStatus(FastTrackStatus.ECG_COMPLETED);
        activation.setLastUpdatedByName(resolveCurrentUserName());

        computeDoorToEcgMinutes(activation);

        // ST elevation confirms STEMI — upgrade ONLY from a pre-ECG NSTEMI/ACS
        // suspicion. Never re-classify a stroke-family activation: an ECG
        // recorded on a stroke patient must not flip the pathway to STEMI.
        if (Boolean.TRUE.equals(request.getStElevation())
                && activation.getFastTrackType() == FastTrackType.NSTEMI_SUSPECTED) {
            activation.setFastTrackType(FastTrackType.STEMI_SUSPECTED);
            log.info("Fast-track type upgraded to STEMI based on ECG ST elevation, id={}", activationId);
        }

        activation = fastTrackActivationRepository.save(activation);
        publishDashboardEvent(activation, "ECG_RECORDED");

        log.info("ECG recorded for fast-track: id={}, stElevation={}, doorToEcg={} min",
                activationId, request.getStElevation(), activation.getDoorToEcgMinutes());
        return activation;
    }

    /**
     * Record CT result for a fast-track activation.
     * Updates status, computes door-to-CT time, and produces an ADVISORY
     * thrombolysis window assessment (the system flags the window — it does NOT
     * clear contraindications, which remain the clinician's decision).
     */
    @Transactional
    public FastTrackActivation recordCt(UUID activationId, CtResultRequest request) {
        FastTrackActivation activation = loadModifiable(activationId);

        Instant now = Instant.now();
        activation.setCtCompletedAt(now);
        activation.setCtResult(request.getCtResult());
        activation.setIsHemorrhagic(request.getIsHemorrhagic());
        activation.setStatus(FastTrackStatus.CT_COMPLETED);
        activation.setLastUpdatedByName(resolveCurrentUserName());

        computeDoorToCtMinutes(activation);

        // Assess for the whole stroke FAMILY (STROKE + TIA): a CT is exactly how
        // hemorrhage is ruled in/out, and a TIA labelling routinely turns out to
        // be an evolving stroke — gating on the exact type would skip the
        // hemorrhagic-CONTRAINDICATED flag for those cases.
        if (isStrokeFamily(activation.getFastTrackType())) {
            assessThrombolysisWindow(activation, now);
        }

        activation = fastTrackActivationRepository.save(activation);
        publishDashboardEvent(activation, "CT_RECORDED");

        log.info("CT recorded for fast-track: id={}, hemorrhagic={}, doorToCt={} min",
                activationId, request.getIsHemorrhagic(), activation.getDoorToCtMinutes());
        return activation;
    }

    /**
     * Get active fast-tracks for a hospital, optionally filtered to a single ED zone.
     */
    public List<FastTrackActivation> getActiveFastTracks(UUID hospitalId, EdZone zone) {
        List<FastTrackActivation> all = fastTrackActivationRepository.findActiveFastTracksByHospital(hospitalId);
        if (zone == null) return all;
        return all.stream()
                .filter(a -> a.getVisit() != null && a.getVisit().getCurrentEdZone() == zone)
                .toList();
    }

    /** Back-compat overload — full hospital-wide list. */
    public List<FastTrackActivation> getActiveFastTracks(UUID hospitalId) {
        return getActiveFastTracks(hospitalId, null);
    }

    /**
     * Get the most recent fast-track for a visit.
     */
    public FastTrackActivation getFastTrack(UUID visitId) {
        return fastTrackActivationRepository.findFirstByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("FastTrackActivation", "visitId", visitId));
    }

    /** Most recent fast-track for a visit, or null when none exists (no 404). */
    public FastTrackActivation getFastTrackOrNull(UUID visitId) {
        return fastTrackActivationRepository.findFirstByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(visitId)
                .orElse(null);
    }

    /**
     * Non-binding decision support: run the stroke/MI detection engine against
     * the visit's latest triage and return the higher-confidence recommendation
     * (or null). This is advisory only — it never auto-activates a pathway.
     */
    public StrokeMIDetectionEngine.FastTrackRecommendation recommend(UUID visitId) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));
        TriageRecord triage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visitId)
                .orElse(null);
        if (triage == null) return null;
        StrokeMIDetectionEngine.FastTrackRecommendation stroke = strokeMIDetectionEngine.screenForStroke(visit, triage);
        StrokeMIDetectionEngine.FastTrackRecommendation mi = strokeMIDetectionEngine.screenForMI(visit, triage);
        if (stroke == null) return mi;
        if (mi == null) return stroke;
        return stroke.confidence() >= mi.confidence() ? stroke : mi;
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private FastTrackActivation loadModifiable(UUID activationId) {
        FastTrackActivation activation = fastTrackActivationRepository.findByIdAndIsActiveTrue(activationId)
                .orElseThrow(() -> new ResourceNotFoundException("FastTrackActivation", "id", activationId));
        if (activation.getStatus() == FastTrackStatus.COMPLETED
                || activation.getStatus() == FastTrackStatus.CANCELLED) {
            throw new ClinicalBusinessException(
                    "Fast-track " + activationId + " is already " + activation.getStatus() + " — cannot modify.");
        }
        return activation;
    }

    /** STROKE and TIA are one family; STEMI and NSTEMI are the MI/ACS family. */
    private boolean sameFamily(FastTrackType a, FastTrackType b) {
        return family(a).equals(family(b));
    }

    private String family(FastTrackType t) {
        return (t == FastTrackType.STROKE_SUSPECTED || t == FastTrackType.TIA_SUSPECTED) ? "stroke" : "MI/ACS";
    }

    private void assessThrombolysisWindow(FastTrackActivation activation, Instant now) {
        if (Boolean.TRUE.equals(activation.getIsHemorrhagic())) {
            activation.setThrombolysisEligible(false);
            activation.setThrombolysisAdvisory(
                    "Hemorrhagic findings on CT — IV thrombolysis CONTRAINDICATED. Urgent stroke-team / neurosurgical referral.");
            return;
        }
        if (activation.getSymptomOnsetTime() == null) {
            activation.setThrombolysisEligible(null);
            activation.setThrombolysisAdvisory(
                    "Last-known-well / symptom-onset time UNKNOWN — the thrombolysis time window cannot be assessed. "
                    + "Establish onset before deciding. ADVISORY ONLY: confirm BP <185/110, glucose, anticoagulation/INR "
                    + "and other contraindications.");
            return;
        }
        long minutesSinceOnset = Duration.between(activation.getSymptomOnsetTime(), now).toMinutes();
        boolean within = minutesSinceOnset <= TPA_EXTENDED_WINDOW_MIN;
        activation.setThrombolysisEligible(within);
        String tier;
        if (minutesSinceOnset <= TPA_STANDARD_WINDOW_MIN) {
            tier = "within the 0–3 h IV-tPA window";
        } else if (minutesSinceOnset <= TPA_EXTENDED_WINDOW_MIN) {
            tier = "within the extended 3–4.5 h IV-tPA window (stricter eligibility criteria apply)";
        } else {
            tier = "OUTSIDE the 4.5 h IV-tPA window — consider mechanical thrombectomy for large-vessel occlusion "
                    + "(selected patients up to 24 h with advanced imaging)";
        }
        activation.setThrombolysisAdvisory(String.format(
                "Onset→CT %d min (%s). ADVISORY ONLY — the system flags the time window, it does NOT clear "
                + "contraindications: the actual needle time will be later than this CT; before treating, confirm "
                + "BP <185/110, glucose, anticoagulation/INR, recent surgery/bleeding and platelet count.",
                minutesSinceOnset, tier));
    }

    private void computeDoorToEcgMinutes(FastTrackActivation activation) {
        if (activation.getEcgCompletedAt() != null && activation.getVisit().getArrivalTime() != null) {
            long minutes = Duration.between(activation.getVisit().getArrivalTime(),
                    activation.getEcgCompletedAt()).toMinutes();
            activation.setDoorToEcgMinutes((int) minutes);
            if (minutes > 10) {
                log.warn("Door-to-ECG exceeded target: {} minutes (target < 10 min), fast-track {}",
                        minutes, activation.getId());
            }
        }
    }

    private void computeDoorToCtMinutes(FastTrackActivation activation) {
        if (activation.getCtCompletedAt() != null && activation.getVisit().getArrivalTime() != null) {
            long minutes = Duration.between(activation.getVisit().getArrivalTime(),
                    activation.getCtCompletedAt()).toMinutes();
            activation.setDoorToCtMinutes((int) minutes);
            if (minutes > 25) {
                log.warn("Door-to-CT exceeded target: {} minutes (target < 25 min), fast-track {}",
                        minutes, activation.getId());
            }
        }
    }

    private void computeDoorToNeedleMinutes(FastTrackActivation activation) {
        Instant needleTime = activation.getThrombolysisStartedAt();
        if (needleTime == null) {
            needleTime = activation.getReferredForPciAt();
        }
        if (needleTime != null && activation.getVisit().getArrivalTime() != null) {
            long minutes = Duration.between(activation.getVisit().getArrivalTime(), needleTime).toMinutes();
            activation.setDoorToNeedleMinutes((int) minutes);
        }
    }

    private void generateFastTrackAlert(Visit visit, FastTrackActivation activation) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();

        // Resolve the accountable zone doctor so the alert is OWNED, not
        // hospital-generic — the doctor on this patient's zone runs the clock.
        User zoneDoctor = null;
        if (hospitalId != null && zone != null) {
            List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
            if (!doctors.isEmpty()) zoneDoctor = doctors.get(0);
        }

        String patientName = visit.getPatient() != null
                ? (visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName())
                : "patient";
        String target = isStrokeFamily(activation.getFastTrackType())
                ? "target door-to-CT < 25 min"
                : "target door-to-ECG < 10 min";

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.FAST_TRACK_ACTIVATED)
                .severity(AlertSeverity.CRITICAL)
                .title("FAST-TRACK ACTIVATED — " + prettyType(activation.getFastTrackType()))
                .message(String.format(
                        "Time-critical %s pathway activated for %s (Visit: %s) by %s. "
                        + "Door-to-treatment clock is RUNNING — %s. Acknowledge and act immediately.",
                        prettyType(activation.getFastTrackType()), patientName, visit.getVisitNumber(),
                        activation.getActivatedByName() != null ? activation.getActivatedByName() : "System",
                        target))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(1)
                .autoGenerated(true)
                .build();

        alert = clinicalAlertRepository.save(alert);
        publishFastTrackAlert(alert, hospitalId, zone, zoneDoctor);

        log.warn("FAST-TRACK ALERT generated: visit={}, type={}, zone={}, doctor={}",
                visit.getId(), activation.getFastTrackType(), zone,
                zoneDoctor != null ? zoneDoctor.getId() : "unassigned");
    }

    /**
     * Push a fast-track alert in real time to the zone board, the accountable
     * zone doctor, and the charge nurse(s) — so a CRITICAL activation is seen
     * immediately. Best-effort: a STOMP failure must never break the activation.
     */
    void publishFastTrackAlert(ClinicalAlert alert, UUID hospitalId, EdZone zone, User zoneDoctor) {
        try {
            if (hospitalId == null || alert == null) return;
            var resp = ClinicalAlertMapper.toResponse(alert);
            // Resolve recipient ids in-transaction, then fan out AFTER COMMIT so a
            // rolled-back activation never pushes a phantom CRITICAL fast-track alert.
            java.util.List<UUID> userIds = new java.util.ArrayList<>();
            if (zoneDoctor != null) userIds.add(zoneDoctor.getId());
            for (User cn : shiftAssignmentService.getChargeNurse(hospitalId)) {
                if (cn != null) userIds.add(cn.getId());
            }
            realTimeEventPublisher.publishOwnedAlertAfterCommit(hospitalId, zone, resp, userIds);
        } catch (Exception e) {
            log.warn("Failed to publish fast-track alert {}: {}",
                    alert != null ? alert.getId() : null, e.getMessage());
        }
    }

    private void publishDashboardEvent(FastTrackActivation activation, String eventType) {
        try {
            UUID hospitalId = activation.getVisit() != null && activation.getVisit().getHospital() != null
                    ? activation.getVisit().getHospital().getId() : null;
            if (hospitalId != null) {
                realTimeEventPublisher.publishFastTrackEventAfterCommit(hospitalId, Map.of(
                        "eventType", eventType,
                        "visitId", activation.getVisit().getId().toString()));
            }
        } catch (Exception e) {
            log.warn("Failed to publish fast-track dashboard event: {}", e.getMessage());
        }
    }

    private boolean isStrokeFamily(FastTrackType t) {
        return t == FastTrackType.STROKE_SUSPECTED || t == FastTrackType.TIA_SUSPECTED;
    }

    private String prettyType(FastTrackType t) {
        return switch (t) {
            case STROKE_SUSPECTED -> "Suspected Stroke";
            case TIA_SUSPECTED -> "Suspected TIA";
            case STEMI_SUSPECTED -> "Suspected STEMI";
            case NSTEMI_SUSPECTED -> "Suspected NSTEMI / ACS";
        };
    }

    private String resolveCurrentUserName() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) {
                return user.getFirstName() + " " + user.getLastName();
            }
        } catch (Exception ignored) {
            // no authenticated user resolvable (scheduled/system context)
        }
        return null;
    }
}
