package com.smartTriage.smartTriage_server.module.sepsis.service;

import com.smartTriage.smartTriage_server.common.enums.*;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
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

        if (request != null && request.getWbcCount() != null) {
            if (request.getWbcCount() > 12000 || request.getWbcCount() < 4000) {
                wbcCriteriaMet = true;
                sirsScore++;
            }
            if (Boolean.TRUE.equals(request.getWbcBandsElevated())) {
                wbcCriteriaMet = true;
                if (sirsScore == result.sirsScore()) { // Only increment once
                    sirsScore++;
                }
            }
        }

        // Re-evaluate status with updated SIRS score if infection is suspected
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
        screening = sepsisScreeningRepository.save(screening);

        log.info("Sepsis bundle started: Screening={}, Visit={}",
                screeningId, screening.getVisit().getVisitNumber());

        return screening;
    }

    /**
     * Mark a specific bundle item as complete.
     */
    @Transactional
    public SepsisScreening completeBundleItem(UUID screeningId, SepsisBundleItem item) {
        SepsisScreening screening = sepsisScreeningRepository.findByIdAndIsActiveTrue(screeningId)
                .orElseThrow(() -> new ResourceNotFoundException("SepsisScreening", "id", screeningId));

        switch (item) {
            case BLOOD_CULTURE_OBTAINED -> screening.setBloodCultureObtained(true);
            case BROAD_SPECTRUM_ANTIBIOTICS -> screening.setBroadSpectrumAntibiotics(true);
            case IV_CRYSTALLOID_BOLUS -> screening.setIvCrystalloidBolus(true);
            case LACTATE_MEASURED -> screening.setLactateMeasured(true);
            case VASOPRESSORS_IF_NEEDED -> screening.setVasopressorsIfNeeded(true);
            case REPEAT_LACTATE_IF_ELEVATED -> screening.setRepeatLactateIfElevated(true);
        }

        screening = sepsisScreeningRepository.save(screening);

        log.info("Bundle item completed: {} for Screening={}", item.name(), screeningId);

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

        screening.setBundleCompletedAt(Instant.now());

        // Mark all items as complete
        screening.setBloodCultureObtained(true);
        screening.setBroadSpectrumAntibiotics(true);
        screening.setIvCrystalloidBolus(true);
        screening.setLactateMeasured(true);
        screening.setVasopressorsIfNeeded(true);
        screening.setRepeatLactateIfElevated(true);

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

        AlertSeverity severity = AlertSeverity.CRITICAL;
        String title = "SEPSIS DETECTED — " + status.name().replace("_", " ");

        ClinicalAlert alert = ClinicalAlert.builder()
                .visit(visit)
                .alertType(AlertType.SEPSIS_SCREENING)
                .severity(severity)
                .title(title)
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
                .autoGenerated(true)
                .build();

        clinicalAlertRepository.save(alert);

        log.warn("SEPSIS ALERT generated: Visit={}, Status={}, Severity={}",
                visit.getVisitNumber(), status, severity);
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
