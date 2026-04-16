package com.smartTriage.smartTriage_server.module.offline.service;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import com.smartTriage.smartTriage_server.module.offline.dto.HealthCheckRequest;
import com.smartTriage.smartTriage_server.module.offline.entity.SystemHealthStatus;
import com.smartTriage.smartTriage_server.module.offline.repository.SystemHealthStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * SystemHealthService — monitors and records system health status.
 *
 * Generates alerts when internet connectivity changes, enabling
 * the ED team to be aware of offline mode activation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemHealthService {

    private final SystemHealthStatusRepository healthStatusRepository;
    private final HospitalRepository hospitalRepository;
    private final ClinicalAlertRepository clinicalAlertRepository;

    /**
     * Record a system health check. Detects connectivity changes and generates alerts.
     */
    @Transactional
    public SystemHealthStatus recordHealthCheck(UUID hospitalId, HealthCheckRequest request) {
        Hospital hospital = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));

        // Get previous health status to detect changes
        Optional<SystemHealthStatus> previousStatus = healthStatusRepository
                .findFirstByHospitalIdAndIsActiveTrueOrderByCheckTimeDesc(hospitalId);

        Instant now = Instant.now();

        SystemHealthStatus healthStatus = SystemHealthStatus.builder()
                .hospital(hospital)
                .checkTime(now)
                .serverOnline(request.isServerOnline())
                .databaseOnline(request.isDatabaseOnline())
                .internetConnectivity(request.isInternetConnectivity())
                .powerStatus(request.getPowerStatus())
                .lastSuccessfulSync(request.getLastSuccessfulSync())
                .pendingSyncCount(request.getPendingSyncCount())
                .activeOfflineDevices(request.getActiveOfflineDevices())
                .notes(request.getNotes())
                .build();

        healthStatus = healthStatusRepository.save(healthStatus);

        // Detect internet connectivity changes and generate alerts
        if (previousStatus.isPresent()) {
            boolean wasOnline = previousStatus.get().isInternetConnectivity();
            boolean isOnline = request.isInternetConnectivity();

            if (wasOnline && !isOnline) {
                generateOfflineAlert(hospital);
                log.warn("Internet connectivity lost for hospital: {} ({})", hospital.getName(), hospitalId);
            } else if (!wasOnline && isOnline) {
                generateOnlineAlert(hospital);
                log.info("Internet connectivity restored for hospital: {} ({})", hospital.getName(), hospitalId);
            }
        }

        log.debug("Health check recorded for hospital {}: server={}, db={}, internet={}, power={}",
                hospital.getName(), request.isServerOnline(), request.isDatabaseOnline(),
                request.isInternetConnectivity(), request.getPowerStatus());

        return healthStatus;
    }

    /**
     * Get the latest health status for a hospital.
     */
    public SystemHealthStatus getLatestHealth(UUID hospitalId) {
        return healthStatusRepository.findFirstByHospitalIdAndIsActiveTrueOrderByCheckTimeDesc(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("SystemHealthStatus", "hospitalId", hospitalId));
    }

    /**
     * Get health check history for a hospital.
     */
    public Page<SystemHealthStatus> getHealthHistory(UUID hospitalId, Pageable pageable) {
        return healthStatusRepository.findByHospitalIdAndIsActiveTrueOrderByCheckTimeDesc(hospitalId, pageable);
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private void generateOfflineAlert(Hospital hospital) {
        // We need a visit to attach the alert to. For system-level alerts,
        // we create a generic alert. Since ClinicalAlert requires a visit,
        // we log the event and the alert will be visible through the health status API.
        log.warn("SYSTEM OFFLINE - OFFLINE MODE ACTIVE for hospital: {}", hospital.getName());

        // Note: ClinicalAlert requires a visit. For system-level alerts without a visit context,
        // the alert is captured in the health status record and server logs.
        // In production, a system-level notification mechanism (WebSocket, push notification)
        // would broadcast this to all connected clients.
    }

    private void generateOnlineAlert(Hospital hospital) {
        log.info("SYSTEM ONLINE - SYNC IN PROGRESS for hospital: {}", hospital.getName());

        // Same as above — system-level notification without a specific visit context.
    }
}
