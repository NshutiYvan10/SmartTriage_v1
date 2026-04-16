package com.smartTriage.smartTriage_server.module.alert.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
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

    @Transactional
    public ClinicalAlert acknowledgeAlert(UUID alertId) {
        ClinicalAlert alert = clinicalAlertRepository.findByIdAndIsActiveTrue(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalAlert", "id", alertId));

        alert.setAcknowledged(true);
        alert.setAcknowledgedAt(Instant.now());

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
        return alert;
    }
}
