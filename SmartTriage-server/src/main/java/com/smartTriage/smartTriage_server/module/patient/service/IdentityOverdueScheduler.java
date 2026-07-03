package com.smartTriage.smartTriage_server.module.patient.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.Role;
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
import java.util.UUID;

/**
 * Identity-overdue scheduled job (V28 — Direct Resus follow-up).
 *
 * <p>An unidentified patient ("Unknown Alpha") admitted via Direct Resus
 * needs a real identity eventually — for chart correctness, family
 * notification, billing reconciliation, and medico-legal defensibility.
 *
 * <p>Policy — ESCALATING reminders (the registrar owns identity; oversight is
 * pulled in only if it stays unresolved):
 * <ul>
 *   <li><b>&lt; 30 minutes</b>: soft UI cue only (a frontend banner derived from
 *       {@code placeholder_assigned_at}); no persistent alert.</li>
 *   <li><b>&ge; 30 minutes — REGISTRAR reminder</b>: persistent
 *       {@code IDENTITY_UNRESOLVED} alert (severity MEDIUM, tier&nbsp;1) delivered
 *       to the registrar role channel + their notifications feed. Their job to
 *       chase down ID / contact family. Idempotent — one per visit.</li>
 *   <li><b>&ge; 2 hours — CHARGE-NURSE escalation</b>: the registrar reminder
 *       didn't land, so raise a distinct {@code IDENTITY_UNRESOLVED_ESCALATED}
 *       alert (severity HIGH, tier&nbsp;2) to the RESUS zone (charge nurse /
 *       shift lead see it). Distinct type so it can't be deduped against the
 *       tier-1 reminder. Idempotent — one per visit.</li>
 *   <li><b>Never block care</b>. The scheduler raises alerts only.</li>
 * </ul>
 *
 * <p>Schedule: every 5 minutes. Quick scan, idempotent — cheap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityOverdueScheduler {

    /** Tier 1 — remind the registrar their patient still has no identity. */
    private static final Duration REGISTRAR_REMINDER_THRESHOLD = Duration.ofMinutes(30);
    /** Tier 2 — escalate to the charge nurse when it's STILL unresolved. */
    private static final Duration CHARGE_NURSE_ESCALATION_THRESHOLD = Duration.ofHours(2);

    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final ClinicalAlertRepository alertRepository;
    private final RealTimeEventPublisher eventPublisher;

    /**
     * Scan unidentified patients and raise the appropriate escalating reminder
     * per active visit (tier 1 to the registrar at 30m, tier 2 to the charge
     * nurse at 2h). Both tiers idempotent (distinct alert types).
     *
     * <p>Runs every 5 minutes. Initial delay 60s so it doesn't fire the moment
     * the app starts (integration tests / local dev resets).
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void scanForOverdueIdentities() {
        // Scan everything past the SHORTER (tier-1) window; decide the tier per
        // patient by exact age below.
        Instant threshold = Instant.now().minus(REGISTRAR_REMINDER_THRESHOLD);
        List<Patient> overdue = patientRepository.findUnidentifiedOlderThan(threshold);
        if (overdue.isEmpty()) {
            log.trace("[identity-overdue] No unidentified patients past {} threshold", REGISTRAR_REMINDER_THRESHOLD);
            return;
        }

        log.info("[identity-overdue] Found {} unidentified patient(s) past {} — checking reminders",
                overdue.size(), REGISTRAR_REMINDER_THRESHOLD);

        for (Patient patient : overdue) {
            try {
                raiseRemindersForPatient(patient);
            } catch (Exception e) {
                // One bad row shouldn't kill the whole scan
                log.error("[identity-overdue] Failed to process patient {}: {}",
                        patient.getId(), e.getMessage(), e);
            }
        }
    }

    private void raiseRemindersForPatient(Patient patient) {
        var page = visitRepository.findByPatientIdAndIsActiveTrue(patient.getId(),
                PageRequest.of(0, 5));
        if (page.isEmpty()) {
            log.debug("[identity-overdue] Patient {} has no active visit — skipping", patient.getId());
            return;
        }

        Duration age = patient.getPlaceholderAssignedAt() != null
                ? Duration.between(patient.getPlaceholderAssignedAt(), Instant.now())
                : Duration.ZERO;

        for (Visit visit : page.getContent()) {
            UUID hospitalId = visit.getHospital().getId();

            // Tier 1 — registrar reminder (>= 30 min). Idempotent per visit.
            if (age.compareTo(REGISTRAR_REMINDER_THRESHOLD) >= 0
                    && !alertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(
                            visit.getId(), AlertType.IDENTITY_UNRESOLVED)) {
                ClinicalAlert alert = alertRepository.save(ClinicalAlert.builder()
                        .visit(visit)
                        .alertType(AlertType.IDENTITY_UNRESOLVED)
                        .severity(AlertSeverity.MEDIUM)
                        .title("Patient identity unresolved")
                        .message(buildMessage(patient, visit, age, false))
                        .escalationTier(1)
                        .autoGenerated(true)
                        .build());
                var resp = ClinicalAlertMapper.toResponse(alert);
                eventPublisher.publishHospitalAlert(hospitalId, resp);
                eventPublisher.publishRoleAlert(hospitalId, Role.REGISTRAR, resp);
                log.info("[identity-overdue] Tier-1 IDENTITY_UNRESOLVED (registrar) for visit {} (patient {} placeholder={})",
                        visit.getVisitNumber(), patient.getId(), patient.getPlaceholderLabel());
            }

            // Tier 2 — charge-nurse escalation (>= 2 h). Distinct type. Idempotent per visit.
            if (age.compareTo(CHARGE_NURSE_ESCALATION_THRESHOLD) >= 0
                    && !alertRepository.existsByVisitIdAndAlertTypeAndIsActiveTrue(
                            visit.getId(), AlertType.IDENTITY_UNRESOLVED_ESCALATED)) {
                ClinicalAlert alert = alertRepository.save(ClinicalAlert.builder()
                        .visit(visit)
                        .alertType(AlertType.IDENTITY_UNRESOLVED_ESCALATED)
                        .severity(AlertSeverity.HIGH)
                        .title("Patient identity STILL unresolved (>2 hours)")
                        .message(buildMessage(patient, visit, age, true))
                        .targetZone(EdZone.RESUS)
                        .escalationTier(2)
                        .autoGenerated(true)
                        .build());
                var resp = ClinicalAlertMapper.toResponse(alert);
                eventPublisher.publishHospitalAlert(hospitalId, resp);
                eventPublisher.publishZoneAlert(hospitalId, EdZone.RESUS, resp);
                // The registrar stays on the hook too — mirror the escalation to their channel.
                eventPublisher.publishRoleAlert(hospitalId, Role.REGISTRAR, resp);
                log.info("[identity-overdue] Tier-2 IDENTITY_UNRESOLVED_ESCALATED (charge nurse) for visit {} (patient {} placeholder={})",
                        visit.getVisitNumber(), patient.getId(), patient.getPlaceholderLabel());
            }
        }
    }

    private String buildMessage(Patient patient, Visit visit, Duration age, boolean escalated) {
        long minutes = age.toMinutes();
        String displayName = UnidentifiedPatientNameService.buildDisplayName(
                patient.getPlaceholderLabel(), visit.isPediatric());
        String lead = escalated
                ? " has STILL not been identified after "
                : " has been in the system for ";
        return displayName + lead + minutes + " minutes without identity resolution. "
                + "Find ID, tap their card, contact family, or use the chart's 'Set Patient Identity' action.";
    }
}
