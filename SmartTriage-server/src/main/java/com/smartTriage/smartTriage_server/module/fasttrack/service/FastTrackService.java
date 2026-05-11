package com.smartTriage.smartTriage_server.module.fasttrack.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.CtResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.EcgResultRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.dto.FastTrackActivationRequest;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;
import com.smartTriage.smartTriage_server.module.fasttrack.repository.FastTrackActivationRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    private final FastTrackActivationRepository fastTrackActivationRepository;
    private final VisitRepository visitRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;

    /**
     * Activate a fast-track protocol for a visit.
     * Auto-orders ECG for MI, generates a CRITICAL alert.
     */
    @Transactional
    public FastTrackActivation activateFastTrack(FastTrackActivationRequest request) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(request.getVisitId())
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", request.getVisitId()));

        // Check for existing active fast-track
        List<FastTrackStatus> terminalStatuses = List.of(FastTrackStatus.COMPLETED, FastTrackStatus.CANCELLED);
        if (fastTrackActivationRepository.existsByVisitIdAndStatusNotInAndIsActiveTrue(
                request.getVisitId(), terminalStatuses)) {
            log.warn("Fast-track already active for visit {}", request.getVisitId());
            throw new IllegalStateException("A fast-track protocol is already active for this visit");
        }

        Instant now = Instant.now();

        FastTrackActivation activation = FastTrackActivation.builder()
                .visit(visit)
                .fastTrackType(request.getFastTrackType())
                .status(FastTrackStatus.ACTIVATED)
                .activatedAt(now)
                .activatedByName(request.getActivatedByName())
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

        // Generate CRITICAL clinical alert
        generateFastTrackAlert(visit, activation);

        log.info("Fast-track activated: type={}, visit={}, id={}",
                activation.getFastTrackType(), visit.getId(), activation.getId());

        return activation;
    }

    /**
     * Update the status of a fast-track activation.
     * Computes door-to-X times when relevant status transitions occur.
     */
    @Transactional
    public FastTrackActivation updateStatus(UUID activationId, FastTrackStatus newStatus) {
        FastTrackActivation activation = fastTrackActivationRepository.findByIdAndIsActiveTrue(activationId)
                .orElseThrow(() -> new ResourceNotFoundException("FastTrackActivation", "id", activationId));

        activation.setStatus(newStatus);

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
                computeDoorToNeedleMinutes(activation);
            }
            case CANCELLED -> activation.setCompletedAt(now);
            default -> { /* no additional action */ }
        }

        activation = fastTrackActivationRepository.save(activation);

        log.info("Fast-track status updated: id={}, newStatus={}", activationId, newStatus);
        return activation;
    }

    /**
     * Record ECG result for a fast-track activation.
     * Updates status and computes door-to-ECG time.
     */
    @Transactional
    public FastTrackActivation recordEcg(UUID activationId, EcgResultRequest request) {
        FastTrackActivation activation = fastTrackActivationRepository.findByIdAndIsActiveTrue(activationId)
                .orElseThrow(() -> new ResourceNotFoundException("FastTrackActivation", "id", activationId));

        Instant now = Instant.now();
        activation.setEcgCompletedAt(now);
        activation.setEcgResult(request.getEcgResult());
        activation.setStElevation(request.getStElevation());
        activation.setStatus(FastTrackStatus.ECG_COMPLETED);

        // Compute door-to-ECG minutes
        computeDoorToEcgMinutes(activation);

        // If ST elevation detected, update type to STEMI
        if (Boolean.TRUE.equals(request.getStElevation())
                && activation.getFastTrackType() != FastTrackType.STEMI_SUSPECTED) {
            activation.setFastTrackType(FastTrackType.STEMI_SUSPECTED);
            log.info("Fast-track type upgraded to STEMI based on ECG ST elevation, id={}", activationId);
        }

        activation = fastTrackActivationRepository.save(activation);

        log.info("ECG recorded for fast-track: id={}, stElevation={}, doorToEcg={} min",
                activationId, request.getStElevation(), activation.getDoorToEcgMinutes());
        return activation;
    }

    /**
     * Record CT result for a fast-track activation.
     * Updates status and computes door-to-CT time.
     * Determines thrombolysis eligibility based on hemorrhagic vs ischemic.
     */
    @Transactional
    public FastTrackActivation recordCt(UUID activationId, CtResultRequest request) {
        FastTrackActivation activation = fastTrackActivationRepository.findByIdAndIsActiveTrue(activationId)
                .orElseThrow(() -> new ResourceNotFoundException("FastTrackActivation", "id", activationId));

        Instant now = Instant.now();
        activation.setCtCompletedAt(now);
        activation.setCtResult(request.getCtResult());
        activation.setIsHemorrhagic(request.getIsHemorrhagic());
        activation.setStatus(FastTrackStatus.CT_COMPLETED);

        // Compute door-to-CT minutes
        computeDoorToCtMinutes(activation);

        // Determine thrombolysis eligibility (ischemic stroke within 4.5-hour window)
        if (activation.getFastTrackType() == FastTrackType.STROKE_SUSPECTED) {
            boolean eligible = !Boolean.TRUE.equals(request.getIsHemorrhagic());
            if (eligible && activation.getSymptomOnsetTime() != null) {
                long minutesSinceOnset = Duration.between(activation.getSymptomOnsetTime(), now).toMinutes();
                eligible = minutesSinceOnset <= 270; // 4.5 hours = 270 minutes
            }
            activation.setThrombolysisEligible(eligible);
            log.info("Thrombolysis eligibility for fast-track {}: {}", activationId, eligible);
        }

        activation = fastTrackActivationRepository.save(activation);

        log.info("CT recorded for fast-track: id={}, hemorrhagic={}, doorToCt={} min",
                activationId, request.getIsHemorrhagic(), activation.getDoorToCtMinutes());
        return activation;
    }

    /**
     * Get active fast-tracks for a hospital, optionally filtered to a
     * single ED zone.
     */
    public List<FastTrackActivation> getActiveFastTracks(UUID hospitalId,
                                                          com.smartTriage.smartTriage_server.common.enums.EdZone zone) {
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

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private void computeDoorToEcgMinutes(FastTrackActivation activation) {
        if (activation.getEcgCompletedAt() != null && activation.getVisit().getArrivalTime() != null) {
            long minutes = Duration.between(activation.getVisit().getArrivalTime(),
                    activation.getEcgCompletedAt()).toMinutes();
            activation.setDoorToEcgMinutes((int) minutes);

            // Rwanda target: door-to-ECG < 10 minutes
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

            // Rwanda target: door-to-CT < 25 minutes
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
        String title = String.format("FAST-TRACK ACTIVATED: %s", activation.getFastTrackType().name());
        String message = String.format(
                "Fast-track protocol activated for %s. Visit: %s. Activated by: %s. Immediate action required.",
                activation.getFastTrackType().name(),
                visit.getVisitNumber(),
                activation.getActivatedByName() != null ? activation.getActivatedByName() : "System");

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.VITAL_SIGN_ABNORMAL)
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message)
                .autoGenerated(true)
                .escalationTier(1)
                .build();

        clinicalAlertRepository.save(alert);
        log.info("CRITICAL alert generated for fast-track activation: visit={}", visit.getId());
    }
}
