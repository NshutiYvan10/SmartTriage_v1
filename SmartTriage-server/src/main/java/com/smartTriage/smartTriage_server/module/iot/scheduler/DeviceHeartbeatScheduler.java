package com.smartTriage.smartTriage_server.module.iot.scheduler;

import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
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
 *   2. If device was MONITORING a patient, the disconnect is handled in a
 *      transaction by {@link DeviceService#handleMonitoringDeviceDisconnect}
 *      (CRITICAL alert + last-reading snapshot + session → DISCONNECTED).
 *
 * This is a fail-safe mechanism: even if the network drops, the system
 * detects the absence of data and alerts clinical staff.
 *
 * NB: the monitored-disconnect handling MUST run in a service-layer
 * transaction. This scheduler runs with no open JPA session
 * (spring.jpa.open-in-view=false), so resolving the lazy visit/patient graph
 * here directly would throw LazyInitializationException — which previously
 * silently skipped the alert, the snapshot, and the DISCONNECTED transition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceHeartbeatScheduler {

    private final DeviceService deviceService;

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
                // A monitored patient has lost their device. Delegate to a
                // @Transactional service method so the lazy visit/patient graph
                // resolves correctly (this scheduler has no open session — see
                // class note). Wrapped so one device's failure can't abort the
                // whole tick; the MonitoringStateWatcher also backstops.
                try {
                    deviceService.handleMonitoringDeviceDisconnect(device.getId());
                } catch (Exception e) {
                    log.warn("Failed to handle monitoring disconnect for device {}: {}",
                            device.getSerialNumber(), e.getMessage());
                }
            }
        }
    }
}
