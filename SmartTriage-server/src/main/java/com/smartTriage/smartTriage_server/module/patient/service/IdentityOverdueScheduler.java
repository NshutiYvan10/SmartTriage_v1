package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.patient.repository.PatientRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Identity-overdue scheduled job (V28 — Direct Resus follow-up).
 *
 * <p>An unidentified patient ("Unknown Alpha") admitted via Direct Resus
 * needs a real identity eventually — for chart correctness, family
 * notification, billing reconciliation, and medico-legal defensibility.
 *
 * <p>Policy (locked with the user during design):
 * <ul>
 *   <li><b>30 minutes</b>: soft UI cue on the visit page (no
 *       persistent alert; a frontend banner derived from
 *       {@code placeholder_assigned_at}).</li>
 *   <li><b>2 hours</b>: persistent {@code IDENTITY_UNRESOLVED} alert at
 *       severity HIGH, raised by this scheduler. Targeted at the
 *       resus zone (the charge nurse / receiving doctor see it).
 *       Idempotent — only one alert per visit.</li>
 *   <li><b>Never block care</b>. The scheduler raises alerts only —
 *       it never modifies clinical workflow.</li>
 * </ul>
 *
 * <p>Schedule: every 5 minutes. Quick scan, idempotent — cheap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityOverdueScheduler {

    /** Threshold after which we raise an IDENTITY_UNRESOLVED alert. */
    private static final Duration ALERT_THRESHOLD = Duration.ofHours(2);

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final ClinicalAlertRepository alertRepository;
    private final RealTimeEventPublisher eventPublisher;

    /**
     * Scan unidentified patients past the 2-hour threshold and raise
     * a one-time {@code IDENTITY_UNRESOLVED} alert per active visit.
     *
     * <p>Runs every 5 minutes. Initial delay is 60s so it doesn't fire
     * the moment the app starts (e.g. during integration tests or local
     * dev resets).
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void scanForOverdueIdentities() {
        Instant threshold = Instant.now().minus(ALERT_THRESHOLD);
        List<Patient> overdue = patientRepository.findUnidentifiedOlderThan(threshold);
        if (overdue.isEmpty()) {
            log.trace("[identity-overdue] No unidentified patients past {} threshold", ALERT_THRESHOLD);
            return;
        }

        log.info("[identity-overdue] Found {} unidentified patient(s) past {} — checking for missing alerts",
                overdue.size(), ALERT_THRESHOLD);

        for (Patient patient : overdue) {
            try {
                raiseAlertForPatient(patient);
            } catch (Exception e) {
                // One bad row shouldn't kill the whole scan
                log.error("[identity-overdue] Failed to process patient {}: {}",
                        patient.getId(), e.getMessage(), e);
            }
        }
    }

    private void raiseAlertForPatient(Patient patient) {
        // Find the patient's most recent active visit. For Direct Resus
        // arrivals there's typically exactly one, but a placeholder
        // patient could in principle have a follow-up visit too.
        var page = visitRepository.findByPatientIdAndIsActiveTrue(patient.getId(),
                PageRequest.of(0, 5));
        if (page.isEmpty()) {
            log.debug("[identity-overdue] Patient {} has no active visit — skipping",
                    patient.getId());
            return;
        }

        for (Visit visit : page.getContent()) {
            // Idempotency: skip if we've already raised the alert
            boolean already = alertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(
                    visit.getId(), AlertType.IDENTITY_UNRESOLVED);
            if (already) continue;

            ClinicalAlert alert = ClinicalAlert.builder()
                    .visit(visit)
                    .alertType(AlertType.IDENTITY_UNRESOLVED)
                    .severity(AlertSeverity.HIGH)
                    .title("Patient identity unresolved (>2 hours)")
                    .message(buildMessage(patient, visit))
                    .targetZone(EdZone.RESUS)
                    .escalationTier(1)
                    .autoGenerated(true)
                    .build();
            alert = alertRepository.save(alert);

            eventPublisher.publishHospitalAlert(visit.getHospital().getId(),
                    ClinicalAlertMapper.toResponse(alert));
            eventPublisher.publishZoneAlert(visit.getHospital().getId(), EdZone.RESUS,
                    ClinicalAlertMapper.toResponse(alert));

            log.info("[identity-overdue] Raised IDENTITY_UNRESOLVED for visit {} (patient {} placeholder={})",
                    visit.getVisitNumber(), patient.getId(), patient.getPlaceholderLabel());
        }
    }

    private String buildMessage(Patient patient, Visit visit) {
        long minutes = Duration.between(patient.getPlaceholderAssignedAt(), Instant.now()).toMinutes();
        String displayName = UnidentifiedPatientNameService.buildDisplayName(
                patient.getPlaceholderLabel(), visit.isPediatric());
        return displayName
                + " has been in the system for " + minutes + " minutes without identity resolution. "
                + "Find ID, contact family, or use the chart's 'Set Patient Identity' action.";
    }
}
