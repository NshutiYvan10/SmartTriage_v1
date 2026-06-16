package com.smartTriage.smartTriage_server.module.sepsis.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;
import com.smartTriage.smartTriage_server.module.sepsis.repository.SepsisScreeningRepository;
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
import java.util.UUID;

/**
 * SepsisBundleMonitorService — scheduled monitor for sepsis bundle compliance.
 *
 * Runs every 60 seconds and checks:
 *   1. Bundles in progress that exceed 60 minutes → CRITICAL "SEPSIS BUNDLE OVERDUE"
 *   2. Sepsis screenings > 15 minutes old where bundle was never started → CRITICAL "SEPSIS BUNDLE NOT STARTED"
 *
 * The escalations use DISTINCT alert types (SEPSIS_BUNDLE_OVERDUE /
 * SEPSIS_BUNDLE_NOT_STARTED) and dedup on those — so an unacknowledged original
 * SEPSIS_SCREENING detection alert can NO LONGER suppress them. Previously the
 * shared SEPSIS_SCREENING type meant the escalation was silenced for exactly the
 * patients nobody had acted on. Each escalation is also routed to the zone doctor
 * + charge nurse and pushed in real time, not save-only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SepsisBundleMonitorService {

    private final SepsisScreeningRepository sepsisScreeningRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;
    private final ShiftAssignmentService shiftAssignmentService;
    private final RealTimeEventPublisher realTimeEventPublisher;

    private static final long BUNDLE_COMPLETION_DEADLINE_MINUTES = 60;
    private static final long BUNDLE_START_DEADLINE_MINUTES = 15;

    private static final List<SepsisStatus> BUNDLE_REQUIRED_STATUSES = List.of(
            SepsisStatus.SEPSIS_SUSPECTED,
            SepsisStatus.SEVERE_SEPSIS,
            SepsisStatus.SEPTIC_SHOCK
    );

    @Scheduled(fixedDelayString = "${smarttriage.sepsis.bundle-check-interval-ms:60000}")
    @Transactional
    public void checkBundleCompliance() {
        int alertsGenerated = checkOverdueBundles() + checkUnstartedBundles();
        if (alertsGenerated > 0) {
            log.info("Sepsis bundle monitor: generated {} compliance alerts", alertsGenerated);
        }
    }

    /** Bundles in progress for more than 60 minutes. */
    private int checkOverdueBundles() {
        List<SepsisScreening> bundlesInProgress = sepsisScreeningRepository.findActiveBundlesInProgress();
        int alertCount = 0;

        for (SepsisScreening screening : bundlesInProgress) {
            long minutesSinceStart = Duration.between(screening.getBundleStartedAt(), Instant.now()).toMinutes();
            if (minutesSinceStart <= BUNDLE_COMPLETION_DEADLINE_MINUTES) continue;

            // Dedup on the DISTINCT escalation type — the original SEPSIS_SCREENING
            // alert no longer blocks this.
            if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                    screening.getVisit().getId(), AlertType.SEPSIS_BUNDLE_OVERDUE)) {
                continue;
            }

            String patientName = screening.getVisit().getPatient().getFirstName() + " "
                    + screening.getVisit().getPatient().getLastName();
            ClinicalAlert alert = buildEscalation(screening, AlertType.SEPSIS_BUNDLE_OVERDUE,
                    "SEPSIS BUNDLE OVERDUE",
                    String.format(
                            "CRITICAL: Sepsis bundle for patient %s (Visit: %s) has been in progress for %d "
                                    + "minutes — exceeds the 60-minute completion deadline. Status: %s. Bundle "
                                    + "started at: %s. IMMEDIATE ACTION REQUIRED: complete all remaining bundle items.",
                            patientName, screening.getVisit().getVisitNumber(), minutesSinceStart,
                            screening.getSepsisStatus().name(), screening.getBundleStartedAt()));
            saveTargetAndPublish(screening, alert);
            alertCount++;
            log.error("SEPSIS BUNDLE OVERDUE: Visit {} | {} minutes since bundle start",
                    screening.getVisit().getVisitNumber(), minutesSinceStart);
        }
        return alertCount;
    }

    /** Sepsis cases where the bundle was never started within 15 minutes of detection. */
    private int checkUnstartedBundles() {
        List<SepsisScreening> unstartedCases = sepsisScreeningRepository
                .findSepsisWithoutBundle(BUNDLE_REQUIRED_STATUSES);
        int alertCount = 0;

        for (SepsisScreening screening : unstartedCases) {
            long minutesSinceScreening = Duration.between(screening.getScreenedAt(), Instant.now()).toMinutes();
            if (minutesSinceScreening <= BUNDLE_START_DEADLINE_MINUTES) continue;

            if (clinicalAlertRepository.existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                    screening.getVisit().getId(), AlertType.SEPSIS_BUNDLE_NOT_STARTED)) {
                continue;
            }

            String patientName = screening.getVisit().getPatient().getFirstName() + " "
                    + screening.getVisit().getPatient().getLastName();
            ClinicalAlert alert = buildEscalation(screening, AlertType.SEPSIS_BUNDLE_NOT_STARTED,
                    "SEPSIS BUNDLE NOT STARTED",
                    String.format(
                            "CRITICAL: Sepsis detected for patient %s (Visit: %s) %d minutes ago but the 1-hour "
                                    + "bundle has NOT been started. Status: %s. Screened at: %s. IMMEDIATE ACTION "
                                    + "REQUIRED: start the sepsis bundle NOW.",
                            patientName, screening.getVisit().getVisitNumber(), minutesSinceScreening,
                            screening.getSepsisStatus().name(), screening.getScreenedAt()));
            saveTargetAndPublish(screening, alert);
            alertCount++;
            log.error("SEPSIS BUNDLE NOT STARTED: Visit {} | {} minutes since screening",
                    screening.getVisit().getVisitNumber(), minutesSinceScreening);
        }
        return alertCount;
    }

    private ClinicalAlert buildEscalation(SepsisScreening screening, AlertType type, String title, String message) {
        Visit visit = screening.getVisit();
        EdZone zone = visit.getCurrentEdZone();
        User zoneDoctor = resolveZoneDoctor(visit, zone);
        return ClinicalAlert.builder()
                .visit(visit)
                .alertType(type)
                .severity(AlertSeverity.CRITICAL)
                .title(title)
                .message(message)
                .targetZone(zone)
                .targetDoctor(zoneDoctor)
                .escalationTier(2) // a missed bundle is a Tier-2 follow-up escalation
                .autoGenerated(true)
                .build();
    }

    private User resolveZoneDoctor(Visit visit, EdZone zone) {
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        if (hospitalId == null || zone == null) return null;
        List<User> doctors = shiftAssignmentService.getDoctorsForZone(hospitalId, zone);
        return doctors.isEmpty() ? null : doctors.get(0);
    }

    /** Persist then push the escalation to the zone board + zone doctor + charge nurse(s). */
    private void saveTargetAndPublish(SepsisScreening screening, ClinicalAlert alert) {
        ClinicalAlert saved = clinicalAlertRepository.save(alert);
        Visit visit = screening.getVisit();
        UUID hospitalId = visit.getHospital() != null ? visit.getHospital().getId() : null;
        EdZone zone = visit.getCurrentEdZone();
        try {
            if (hospitalId == null) return;
            var resp = ClinicalAlertMapper.toResponse(saved);
            realTimeEventPublisher.publishHospitalAlert(hospitalId, resp);
            if (zone != null) realTimeEventPublisher.publishZoneAlert(hospitalId, zone, resp);
            if (saved.getTargetDoctor() != null) {
                realTimeEventPublisher.publishUserAlert(saved.getTargetDoctor().getId(), resp);
            }
            for (User cn : shiftAssignmentService.getChargeNurse(hospitalId)) {
                realTimeEventPublisher.publishUserAlert(cn.getId(), resp);
            }
        } catch (Exception e) {
            log.warn("Failed to publish sepsis bundle escalation {}: {}", saved.getId(), e.getMessage());
        }
    }
}
