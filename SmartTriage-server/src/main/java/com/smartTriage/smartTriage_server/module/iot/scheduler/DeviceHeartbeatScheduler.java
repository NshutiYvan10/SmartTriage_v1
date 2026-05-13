package com.smartTriage.smartTriage_server.module.iot.scheduler;

import com.smartTriage.smartTriage_server.common.enums.AlertSeverity;
import com.smartTriage.smartTriage_server.common.enums.AlertType;
import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.repository.ClinicalAlertRepository;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;

/**
 * DeviceHeartbeatScheduler — periodic task to detect disconnected devices.
 *
 * Runs every 15 seconds to check for devices that have missed their heartbeat
 * deadline. When a device is detected as stale:
 *   1. Device status is set to OFFLINE
 *   2. If device was MONITORING a patient, a CRITICAL alert is generated
 *   3. The monitoring session is closed with reason "Device disconnected"
 *
 * This is a fail-safe mechanism: even if the network drops, the system
 * detects the absence of data and alerts clinical staff.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceHeartbeatScheduler {

    private final DeviceService deviceService;
    private final ClinicalAlertRepository clinicalAlertRepository;

    /**
     * Check for stale devices every 15 seconds.
     */
    @Scheduled(fixedDelayString = "${smarttriage.iot.heartbeat-check-interval-ms:15000}")
    public void checkDeviceHeartbeats() {
        List<IoTDevice> staleDevices = deviceService.findStaleDevices();

        for (IoTDevice device : staleDevices) {
            // Check if this device's specific heartbeat timeout has been exceeded
            Instant deviceCutoff = Instant.now().minusSeconds(device.getHeartbeatTimeoutSeconds());
            if (device.getLastHeartbeatAt() != null
                    && device.getLastHeartbeatAt().isAfter(deviceCutoff)) {
                continue; // Device is within its own timeout — not stale
            }

            boolean wasMonitoring = device.getStatus() == DeviceStatus.MONITORING;

            try {
                // Mark device offline
                deviceService.markDeviceOffline(device);
            } catch (ObjectOptimisticLockingFailureException e) {
                // Another thread (e.g. incoming heartbeat) updated this device concurrently.
                // The device is no longer stale — safe to skip.
                log.debug("Skipping device {} — concurrent update (likely a heartbeat arrived)",
                        device.getSerialNumber());
                continue;
            }

            if (wasMonitoring) {
                // This is critical — a monitored patient has lost their device
                handleMonitoringDeviceDisconnect(device);
            }
        }
    }

    private void handleMonitoringDeviceDisconnect(IoTDevice device) {
        DeviceSession session = deviceService.findActiveSessionForDevice(device.getId());

        if (session != null) {
            // Categorise correctly: monitoring failed, patient may be
            // fine. Using DETERIORATION_DETECTED (CRITICAL) here was the
            // old failure mode that trained clinicians to ignore the
            // deterioration channel. Severity HIGH (not CRITICAL)
            // because the failure is a monitoring loss, not a confirmed
            // patient decline.
            boolean alreadyOpen = clinicalAlertRepository
                    .existsByVisitIdAndAlertTypeAndIsAcknowledgedFalseAndIsActiveTrue(
                            session.getVisit().getId(), AlertType.IOT_DEVICE_DISCONNECTED);
            if (!alreadyOpen) {
                ClinicalAlert alert = ClinicalAlert.builder()
                        .visit(session.getVisit())
                        .alertType(AlertType.IOT_DEVICE_DISCONNECTED)
                        .severity(AlertSeverity.HIGH)
                        .title("Monitor offline — patient unmonitored")
                        .message(String.format(
                                "Monitor '%s' (Serial: %s) for patient %s %s (Visit: %s) " +
                                "has lost its heartbeat (last seen: %s). The patient is no longer " +
                                "being continuously monitored. Check the device, power it back on, " +
                                "or pair a replacement.",
                                device.getDeviceName(),
                                device.getSerialNumber(),
                                session.getVisit().getPatient().getFirstName(),
                                session.getVisit().getPatient().getLastName(),
                                session.getVisit().getVisitNumber(),
                                device.getLastHeartbeatAt()))
                        .autoGenerated(true)
                        .build();
                clinicalAlertRepository.save(alert);
                session.incrementAlerts();
            }

            // Session is NOT closed — transition to DISCONNECTED so that
            // when the device reconnects (or a replacement is paired)
            // the timeline stays one continuous record. Closing here
            // fragmented the clinical chart across every flaky network
            // moment, which is the wrong default for a clinical-safety
            // system.
            deviceService.transitionSessionState(session.getId(),
                    com.smartTriage.smartTriage_server.common.enums.MonitoringState.DISCONNECTED);

            log.warn("Monitor {} disconnected during active session for Visit {}. " +
                      "State transitioned to DISCONNECTED.",
                    device.getSerialNumber(), session.getVisit().getVisitNumber());
        }
    }
}
