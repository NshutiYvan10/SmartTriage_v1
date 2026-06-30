package com.smartTriage.smartTriage_server.module.alert.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.EmsRunStatus;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.ems.mapper.EmsRunMapper;
import com.smartTriage.smartTriage_server.module.ems.repository.EmsRunRepository;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Clinical Alert service — manages system-generated and manually created
 * alerts.
 * Provides the alert queue for the ED dashboard, including zone-aware queries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalAlertService {

    private final ClinicalAlertRepository clinicalAlertRepository;
    private final EmsRunRepository emsRunRepository;
    private final RealTimeEventPublisher eventPublisher;

    public Page<ClinicalAlert> getAlertsForVisit(UUID visitId, Pageable pageable) {
        return clinicalAlertRepository.findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(visitId, pageable);
    }

    public Page<ClinicalAlert> getAllAlerts(UUID hospitalId, Pageable pageable) {
        return clinicalAlertRepository.findAllAlertsByHospital(hospitalId, pageable);
    }

    public Page<ClinicalAlert> getUnacknowledgedAlerts(UUID hospitalId, Pageable pageable) {
        return clinicalAlertRepository.findUnacknowledgedAlerts(hospitalId, pageable);
    }

    public Page<ClinicalAlert> getCriticalAlerts(UUID hospitalId, Pageable pageable) {
        return clinicalAlertRepository.findUnacknowledgedAlertsBySeverity(
                hospitalId, AlertSeverity.CRITICAL, pageable);
    }

    /**
     * Get unacknowledged alerts for a specific ED zone.
     */
    public List<ClinicalAlert> getUnacknowledgedAlertsByZone(UUID hospitalId, EdZone zone) {
        return clinicalAlertRepository.findUnacknowledgedAlertsByZone(hospitalId, zone);
    }

    /**
     * Get unacknowledged alerts targeted at a specific doctor.
     */
    public List<ClinicalAlert> getAlertsForDoctor(UUID doctorId) {
        return clinicalAlertRepository.findUnacknowledgedAlertsForDoctor(doctorId);
    }

    /**
     * Server-side filter for the Phase 14 Override Audit dashboard. The
     * `range` parameter accepts the same shorthand the frontend uses
     * ("24h", "7d", "30d", "all" — case-insensitive). Anything else
     * is treated as "all" rather than throwing, because a malformed
     * query string from a stale link shouldn't take the dashboard down.
     */
    public Page<ClinicalAlert> getSafetyOverrides(
            UUID hospitalId,
            String range,
            Pageable pageable) {
        Instant from = null;
        if (range != null) {
            String normalised = range.trim().toLowerCase();
            Instant now = Instant.now();
            switch (normalised) {
                case "24h" -> from = now.minus(24, ChronoUnit.HOURS);
                case "7d"  -> from = now.minus(7, ChronoUnit.DAYS);
                case "30d" -> from = now.minus(30, ChronoUnit.DAYS);
                default    -> from = null;
            }
        }
        return clinicalAlertRepository.findSafetyOverrides(hospitalId, from, null, pageable);
    }

    @Transactional
    public ClinicalAlert acknowledgeAlert(UUID alertId, String note) {
        ClinicalAlert alert = clinicalAlertRepository.findByIdAndIsActiveTrue(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalAlert", "id", alertId));

        alert.setAcknowledged(true);
        alert.setAcknowledgedAt(Instant.now());
        // B5 — persist the acknowledge/dismiss comment (previously dropped).
        // Capped to the column length defensively.
        if (note != null && !note.isBlank()) {
            String trimmed = note.trim();
            alert.setAcknowledgmentNote(trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed);
        }

        // Resolve acknowledging user
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User user) {
                alert.setAcknowledgedBy(user);
            }
        } catch (Exception e) {
            log.debug("Could not resolve acknowledging user");
        }

        alert = clinicalAlertRepository.save(alert);
        log.info("Alert acknowledged: {} (Type: {} Severity: {} Tier: {})",
                alert.getId(), alert.getAlertType(), alert.getSeverity(), alert.getEscalationTier());

        // Reflect an EMS pre-arrival acknowledgement back to the paramedic:
        // stamp the run so the crew's dashboard shows "ED acknowledged — <name>"
        // (first ack wins). The paramedic otherwise had no "the hospital has
        // seen you" signal until handover.
        if (alert.getAlertType() == AlertType.EMS_PRE_ARRIVAL && alert.getVisit() != null) {
            try {
                String acker = ackerName(alert);
                emsRunRepository.findByVisitIdAndIsActiveTrue(alert.getVisit().getId()).ifPresent(run -> {
                    if (run.getPreArrivalAckedAt() == null
                            && run.getStatus() != EmsRunStatus.HANDED_OFF
                            && run.getStatus() != EmsRunStatus.CANCELLED) {
                        run.setPreArrivalAckedAt(Instant.now());
                        run.setPreArrivalAckedByName(acker);
                        emsRunRepository.save(run);
                        eventPublisher.publishEmsRun(run.getHospital().getId(), EmsRunMapper.toResponse(run));
                    }
                });
            } catch (Exception e) {
                log.warn("Could not stamp EMS pre-arrival ack for visit {}: {}",
                        alert.getVisit().getId(), e.getMessage());
            }
        }
        return alert;
    }

    private static String ackerName(ClinicalAlert alert) {
        User u = alert.getAcknowledgedBy();
        if (u == null) return "ED";
        String n = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                + (u.getLastName() != null ? u.getLastName() : "")).trim();
        return n.isEmpty() ? "ED" : n;
    }
}
