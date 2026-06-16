package com.smartTriage.smartTriage_server.module.sepsis.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.shift.service.ShiftAssignmentService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisScreeningRequest;
import com.smartTriage.smartTriage_server.module.sepsis.engine.SepsisScreeningEngine;
import com.smartTriage.smartTriage_server.module.sepsis.engine.SepsisScreeningEngine.SepsisScreeningResult;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;
import com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SepsisService — orchestrates sepsis screening, bundle management, and alert generation.
 *
 * Key responsibilities:
 *   - Runs sepsis screening using the SepsisScreeningEngine
 *   - Manages the 1-hour sepsis bundle lifecycle (start, item completion, bundle completion)
 *   - Generates CRITICAL alerts when sepsis is detected
 *   - Provides query methods for the sepsis dashboard
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SepsisService {

    private final SepsisScreeningRepository sepsisScreeningRepository;
    private final SepsisScreeningEngine sepsisScreeningEngine;
    private final VisitRepository visitRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    /**
     * Screen a patient for sepsis using their latest vital signs.
     *
     * @param visitId the visit to screen
     * @param request optional override fields (suspected infection source, lactate, WBC, notes)
     * @return the saved SepsisScreening entity
     */
    @Transactional
    public SepsisScreening screenPatient(UUID visitId, SepsisScreeningRequest request) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        VitalSigns latestVitals = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId)
                .orElseThrow(() -> new ClinicalBusinessException(
                        "Cannot perform sepsis screening: no vital signs recorded for visit " + visitId));

        // Deactivate any previous active screening for this visit
        sepsisScreeningRepository.findFirstByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(visitId)
                .ifPresent(previous -> {
                    previous.softDelete();
                    sepsisScreeningRepository.save(previous);
                });

        // Run the screening engine
        SepsisScreeningResult result = sepsisScreeningEngine.screenForSepsis(latestVitals, visit);

        // Apply request overrides and additional WBC criteria
        boolean wbcCriteriaMet = result.wbcCriteriaMet();
        int sirsScore = result.sirsScore();
        SepsisStatus status = result.status();
        String wbcUnitWarning = null;

        if (request != null && request.getWbcCount() != null) {
            double wbc = request.getWbcCount();
            // Unit-safety floor: WBC must be an ABSOLUTE count in cells/µL
            // (criterion thresholds 12,000 / 4,000). A value below 100 is
            // implausible as an absolute count and is almost certainly a
            // ×10^9/L SI mis-entry (e.g. "11.2"). Scoring it would silently
            // turn a NORMAL count into profound leukopenia → a false SIRS point
            // → a false CRITICAL sepsis alert + 1-hour bundle. So an implausible
            // value is IGNORED for scoring and flagged in the data-quality note
            // rather than driving a criterion off a unit error. (Same discipline
            // as the medication dose-unit hazard guard.)
            if (wbc < 100.0) {
                log.warn("Sepsis screen: ignoring implausible WBC {} for visit {} — expected an absolute "
                        + "count in cells/µL, not ×10^9/L", wbc, visitId);
                wbcUnitWarning = "WBC entry " + wbc + " was ignored — implausible as an absolute count "
                        + "(cells/µL); it looks like a ×10^9/L value. Re-enter the absolute count.";
            } else if (wbc > 12000 || wbc < 4000) {
                wbcCriteriaMet = true;
                sirsScore++;
            }
        }
        // Band forms (>10% bands) are an independent SIRS criterion, not tied to
        // the absolute count — evaluate regardless. The "increment once" guard
        // keeps the WBC criterion worth at most one SIRS point.
        if (request != null && Boolean.TRUE.equals(request.getWbcBandsElevated())) {
            wbcCriteriaMet = true;
            if (sirsScore == result.sirsScore()) {
                sirsScore++;
            }
        }

        // CRITICAL: the engine fixed `status` from its vitals-only SIRS score, BEFORE
        // the WBC criterion was added here. Re-evaluate SIRS positivity now, or a
        // patient who reaches SIRS >= 2 ONLY because of the WBC criterion would be
        // silently left NO_SEPSIS — no alert, no bundle — a missed septic patient.
        if (sirsScore >= 2 && status == SepsisStatus.NO_SEPSIS) {
            status = SepsisStatus.SIRS_POSITIVE;
        }

        // Re-evaluate status with updated SIRS score if infection is suspected.
        // SIRS + a suspected infection source IS sepsis by definition.
        if (request != null && request.getSuspectedInfectionSource() != null
                && !request.getSuspectedInfectionSource().isBlank()
                && sirsScore >= 2 && status == SepsisStatus.SIRS_POSITIVE) {
            status = SepsisStatus.SEPSIS_SUSPECTED;
        }

        // Check lactate for severe sepsis escalation
        Double lactateLevel = request != null ? request.getLactateLevel() : null;
        if (lactateLevel != null && lactateLevel > 2.0
                && (status == SepsisStatus.SEPSIS_SUSPECTED || status == SepsisStatus.SIRS_POSITIVE)) {
            status = SepsisStatus.SEVERE_SEPSIS;
        }

        // Fold any WBC unit-safety warning into the data-quality note so the
        // ignored value is visible on the record/dashboard, never silent.
        String dataQualityNote = result.dataQualityNote();
        if (wbcUnitWarning != null) {
            dataQualityNote = (dataQualityNote == null || dataQualityNote.isBlank())
                    ? wbcUnitWarning
                    : dataQualityNote + " " + wbcUnitWarning;
        }

        // Resolve screened-by name
        String screenedByName = resolveCurrentUserName();

        // Build and save the screening entity
        SepsisScreening screening = SepsisScreening.builder()
                .visit(visit)
                .screenedAt(Instant.now())
                .screenedByName(screenedByName)
                .sepsisStatus(status)
                .qsofaScore(result.qsofaScore())
                .sirsScore(sirsScore)
                .alteredMentation(result.alteredMentation())
                .respiratoryRateHigh(result.respiratoryRateHigh())
                .systolicBpLow(result.systolicBpLow())
                .temperatureCriteriaMet(result.temperatureCriteriaMet())
                .heartRateCriteriaMet(result.heartRateCriteriaMet())
                .respiratoryRateCriteriaMet(result.respiratoryRateCriteriaMet())
                .wbcCriteriaMet(wbcCriteriaMet)
                .suspectedInfectionSource(request != null ? request.getSuspectedInfectionSource() : null)
                .lactateLevel(lactateLevel)
                .pediatric(result.pediatric())
                .pediatricCaveat(result.pediatricCaveat())
                .insufficientData(result.insufficientData())
                .dataQualityNote(dataQualityNote)
                .notes(request != null ? request.getNotes() : null)
                .build();

        screening = sepsisScreeningRepository.save(screening);

        // Generate CRITICAL alert if sepsis detected
        if (status == SepsisStatus.SEPSIS_SUSPECTED
                || status == SepsisStatus.SEVERE_SEPSIS
                || status == SepsisStatus.SEPTIC_SHOCK) {
            generateSepsisAlert(visit, screening, status);
        }

        log.info("Sepsis screening completed: Visit={}, Status={}, qSOFA={}, SIRS={}",
                visit.getVisitNumber(), status, result.qsofaScore(), sirsScore);

        return screening;
    }

    /**
     * Screen a patient with no override request.
     */
    @Transactional
    public SepsisScreening screenPatient(UUID visitId) {
        return screenPatient(visitId, null);
    }

    /**
     * Start the 1-hour sepsis bundle timer for a screening.
     */
    @Transactional
    public SepsisScreening startBundle(UUID screeningId) {
        SepsisScreening screening = sepsisScreeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("SepsisScreening", "id", screeningId));

        if (screening.getBundleStartedAt() != null) {
            throw new ClinicalBusinessException(
                    "Bundle already started at " + screening.getBundleStartedAt() + " for screening " + screeningId);
        }

        screening.setBundleStartedAt(Instant.now());
        screening.setBundleStartedByName(resolveCurrentUserName());
        screening = sepsisScreeningRepository.save(screening);

        log.info("Sepsis bundle started: Screening={}, Visit={}, by={}",
                screeningId, screening.getVisit().getVisitNumber(), screening.getBundleStartedByName());

        return screening;
    }

    /**
     * Mark a specific bundle item as complete.
     */
    @Transactional
    public SepsisScreening completeBundleItem(UUID screeningId, SepsisBundleItem item) {
        SepsisScreening screening = sepsisScreeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("SepsisScreening", "id", screeningId));

        Instant now = Instant.now();
        switch (item) {
            case BLOOD_CULTURE_OBTAINED -> { screening.setBloodCultureObtained(true); screening.setBloodCultureObtainedAt(now); }
            case BROAD_SPECTRUM_ANTIBIOTICS -> { screening.setBroadSpectrumAntibiotics(true); screening.setBroadSpectrumAntibioticsAt(now); }
            case IV_CRYSTALLOID_BOLUS -> { screening.setIvCrystalloidBolus(true); screening.setIvCrystalloidBolusAt(now); }
            case LACTATE_MEASURED -> { screening.setLactateMeasured(true); screening.setLactateMeasuredAt(now); }
            case VASOPRESSORS_IF_NEEDED -> { screening.setVasopressorsIfNeeded(true); screening.setVasopressorsIfNeededAt(now); }
            case REPEAT_LACTATE_IF_ELEVATED -> { screening.setRepeatLactateIfElevated(true); screening.setRepeatLactateIfElevatedAt(now); }
        }

        screening = sepsisScreeningRepository.save(screening);

        log.info("Bundle item completed: {} for Screening={} at {}", item.name(), screeningId, now);

        return screening;
    }

    /**
     * Mark the entire bundle as complete.
     */
    @Transactional
    public SepsisScreening completeBundle(UUID screeningId) {
        SepsisScreening screening = sepsisScreeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("SepsisScreening", "id", screeningId));

        if (screening.getBundleStartedAt() == null) {
            throw new ClinicalBusinessException(
                    "Cannot complete bundle that has not been started for screening " + screeningId);
        }

        Instant now = Instant.now();
        screening.setBundleCompletedAt(now);
        screening.setBundleCompletedByName(resolveCurrentUserName());

        // Stamp any not-yet-completed item as done NOW. Items completed earlier
        // keep their own earlier timestamp, so the time-stamped action trail
        // preserves the partial-completion truth (rather than blindly back-dating).
        if (!screening.isBloodCultureObtained()) { screening.setBloodCultureObtained(true); screening.setBloodCultureObtainedAt(now); }
        if (!screening.isBroadSpectrumAntibiotics()) { screening.setBroadSpectrumAntibiotics(true); screening.setBroadSpectrumAntibioticsAt(now); }
        if (!screening.isIvCrystalloidBolus()) { screening.setIvCrystalloidBolus(true); screening.setIvCrystalloidBolusAt(now); }
        if (!screening.isLactateMeasured()) { screening.setLactateMeasured(true); screening.setLactateMeasuredAt(now); }
        if (!screening.isVasopressorsIfNeeded()) { screening.setVasopressorsIfNeeded(true); screening.setVasopressorsIfNeededAt(now); }
        if (!screening.isRepeatLactateIfElevated()) { screening.setRepeatLactateIfElevated(true); screening.setRepeatLactateIfElevatedAt(now); }

        screening = sepsisScreeningRepository.save(screening);

        log.info("Sepsis bundle COMPLETED: Screening={}, Visit={}",
                screeningId, screening.getVisit().getVisitNumber());

        return screening;
    }

    /**
     * Get screening history for a visit.
     */
    public Page<SepsisScreening> getScreenings(UUID visitId, Pageable pageable) {
        return sepsisScreeningRepository.findByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(visitId, pageable);
    }

    /**
     * Get the current active screening for a visit.
     */
    public SepsisScreening getActiveScreening(UUID visitId) {
        return sepsisScreeningRepository.findFirstByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(visitId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "SepsisScreening", "visitId", visitId));
    }

    /**
     * Get all active sepsis cases at a hospital, optionally filtered to
     * a single ED zone. Hospital-wide access is gated by the controller;
     * the zone filter lets an on-shift clinician see only their own
     * zone's cases without needing cross-zone read authority.
     */
    public List<SepsisScreening> getActiveSepsisCases(UUID hospitalId,
                                                       com.smartTriage.smartTriage_server.common.enums.EdZone zone) {
        List<SepsisScreening> all = sepsisScreeningRepository.findActiveSepsisCasesByHospital(hospitalId);
        if (zone == null) return all;
        return all.stream()
                .filter(s -> s.getVisit() != null && s.getVisit().getCurrentEdZone() == zone)
                .toList();
    }

    /** Back-compat overload — full hospital-wide list, no zone filter. */
    public List<SepsisScreening> getActiveSepsisCases(UUID hospitalId) {
        return getActiveSepsisCases(hospitalId, null);
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private void generateSepsisAlert(Visit visit, SepsisScreening screening, SepsisStatus status) {
        String patientName = visit.getPatient().getFirstName() + " " + visit.getPatient().getLastName();
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();

        // Resolve the accountable zone doctor so the alert is OWNED, not
        // hospital-generic — the doctor on this patient's zone is responsible
        // for acting on the 1-hour bundle.
        User zoneDoctor = null;
        if (hospitalId != null && zone != null) {
            List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
            if (!doctors.isEmpty()) zoneDoctor = doctors.get(0);
        }

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.SEPSIS_SCREENING)
                .severity(AlertSeverity.CRITICAL)
                .title("SEPSIS DETECTED — " + status.name().replace("_", " "))
                .message(String.format(
                        "SEPSIS SCREENING POSITIVE for patient %s (Visit: %s). " +
                        "Status: %s. qSOFA: %d/3, SIRS: %d/4. " +
                        "%s " +
                        "1-HOUR SEPSIS BUNDLE MUST BE INITIATED IMMEDIATELY.",
                        patientName,
                        visit.getVisitNumber(),
                        status.name(),
                        screening.getQsofaScore(),
                        screening.getSirsScore(),
                        screening.getLactateLevel() != null
                                ? "Lactate: " + screening.getLactateLevel() + " mmol/L. "
                                : ""))
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(1)
                .autoGenerated(true)
                .build();

        alert = clinicalAlertRepository.save(alert);
        publishSepsisAlert(alert, hospitalId, zone, zoneDoctor);
        // Live refresh for the Sepsis dashboard / per-visit panel via the
        // dedicated sepsis topic, AFTER commit so a refetch sees this saved
        // screening. Best-effort (never breaks the screening transaction).
        if (hospitalId != null) {
            realTimeEventPublisher.publishSepsisEventAfterCommit(hospitalId, java.util.Map.of(
                    "eventType", "SCREENING_POSITIVE",
                    "visitId", visit.getId().toString(),
                    "sepsisStatus", status.name()));
        }

        log.warn("SEPSIS ALERT generated: Visit={}, Status={}, zone={}, doctor={}",
                visit.getVisitNumber(), status, zone, zoneDoctor != null ? zoneDoctor.getId() : "unassigned");
    }

    /**
     * Push a sepsis alert in real time to the zone board, the accountable zone
     * doctor, and the charge nurse(s) in parallel — so a CRITICAL detection is
     * seen immediately, not only on a later REST refresh. Best-effort: a STOMP
     * failure must never break the screening transaction. Reused for the bundle
     * monitor's escalation alerts too.
     */
    void publishSepsisAlert(ClinicalAlert alert, UUID hospitalId, EdZone zone, User zoneDoctor) {
        try {
            if (hospitalId == null || alert == null) return;
            var resp = ClinicalAlertMapper.toResponse(alert);
            realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
            if (zone != null) {
                realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
            }
            if (zoneDoctor != null) {
                realTimeEventPublisher.publishUserAlert(zoneDoctor.getId(), resp);
            }
            for (User cn : shiftAssignmentService.getChargeNurse(hospitalId)) {
                realTimeEventPublisher.publishUserAlert(cn.getId(), resp);
            }
        } catch (Exception e) {
            log.warn("Failed to publish sepsis alert {}: {}",
                    alert != null ? alert.getId() : null, e.getMessage());
        }
    }

    private String resolveCurrentUserName() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof com.smartTriage.smartTriage_server.module.user.entity.User user) {
                return user.getFirstName() + " " + user.getLastName();
            }
        } catch (Exception e) {
            log.debug("Could not resolve current user name for sepsis screening");
        }
        return "System";
    }
}
