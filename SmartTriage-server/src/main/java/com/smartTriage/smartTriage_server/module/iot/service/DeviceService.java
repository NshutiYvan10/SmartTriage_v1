package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.exception.DuplicateResourceException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.service.HospitalService;
import com.smartTriage.smartTriage_server.module.iot.dto.*;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.mapper.IoTMapper;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import com.smartTriage.smartTriage_server.module.iot.service.RealTimeEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * DeviceService — manages IoT device lifecycle and monitoring sessions.
 *
 * Responsibilities:
 * - Device registration and API key provisioning
 * - Device authentication (API key lookup)
 * - Heartbeat processing
 * - Monitoring session management (start / stop / query)
 * - Device status transitions
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeviceService {

    private final IoTDeviceRepository deviceRepository;
    private final DeviceSessionRepository sessionRepository;
    private final HospitalService hospitalService;
    private final VisitService visitService;
    private final RealTimeEventPublisher eventPublisher;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ====================================================================
    // DEVICE REGISTRATION
    // ====================================================================

    /**
     * Register a new IoT device. Generates a unique API key for device
     * authentication.
     */
    @Transactional
    public DeviceResponse registerDevice(RegisterDeviceRequest request) {
        // Check serial number uniqueness
        deviceRepository.findBySerialNumberAndIsActiveTrue(request.getSerialNumber())
                .ifPresent(d -> {
                    throw new DuplicateResourceException("IoTDevice", "serialNumber", request.getSerialNumber());
                });

        Hospital hospital = hospitalService.findHospitalOrThrow(request.getHospitalId());

        String apiKey = generateApiKey();

        IoTDevice device = IoTDevice.builder()
                .serialNumber(request.getSerialNumber())
                .deviceName(request.getDeviceName())
                .deviceType(request.getDeviceType())
                .hospital(hospital)
                .apiKey(apiKey)
                .status(DeviceStatus.REGISTERED)
                .firmwareVersion(request.getFirmwareVersion())
                .macAddress(request.getMacAddress())
                .location(request.getLocation())
                .heartbeatTimeoutSeconds(
                        request.getHeartbeatTimeoutSeconds() != null
                                ? request.getHeartbeatTimeoutSeconds()
                                : 30)
                .dataIntervalSeconds(
                        request.getDataIntervalSeconds() != null
                                ? request.getDataIntervalSeconds()
                                : 5)
                .notes(request.getNotes())
                .build();

        device = deviceRepository.save(device);

        log.info("IoT device registered: {} (Serial: {}, Hospital: {}, Type: {})",
                device.getDeviceName(), device.getSerialNumber(),
                hospital.getName(), device.getDeviceType());

        // Return with API key visible (only time it's returned in full)
        DeviceResponse response = IoTMapper.toResponse(device);
        response.setApiKey(apiKey);
        return response;
    }

    // ====================================================================
    // DEVICE POWER ON / OFF (admin activates the physical device)
    // ====================================================================

    /**
     * Admin toggles the device's service status (V53). The previous
     * power-on / power-off endpoints conflated runtime connection
     * state with admin inventory state — splitting them lets the
     * device's heartbeats keep the runtime state honest while the
     * admin owns the "is this part of our pool" decision.
     *
     * Returns the device to service:
     *   - sets {@code inService = true}
     *   - if currently DECOMMISSIONED, returns runtime status to REGISTERED
     *
     * Takes the device out of service:
     *   - if currently MONITORING, ends the session with a reason
     *   - sets {@code inService = false}
     *   - runtime status becomes OFFLINE (it's no longer reachable
     *     from the active pool's point of view)
     */
    @Transactional
    public DeviceResponse setInService(UUID deviceId, boolean inService) {
        IoTDevice device = findDeviceOrThrow(deviceId);

        if (inService == device.isInService()) {
            // Idempotent — return the unchanged device rather than 4xx,
            // so a double-click on the toggle doesn't surface as an
            // error toast.
            return IoTMapper.toResponse(device);
        }

        if (inService) {
            device.setInService(true);
            if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
                device.setStatus(DeviceStatus.REGISTERED);
            }
            log.info("Device {} returned to service by admin", device.getSerialNumber());
        } else {
            if (device.getStatus() == DeviceStatus.MONITORING) {
                stopMonitoringForDevice(deviceId, "Device taken out of service");
            }
            device.setInService(false);
            device.setStatus(DeviceStatus.OFFLINE);
            device.setBatteryLevel(null);
            device.setWifiRssi(null);
            log.info("Device {} taken out of service by admin", device.getSerialNumber());
        }

        device = deviceRepository.save(device);
        publishDeviceStatus(device);
        return IoTMapper.toResponse(device);
    }

    /**
     * @deprecated Use {@link #setInService(UUID, boolean)} with
     * {@code inService = true}. Kept as a thin alias so any in-flight
     * client builds don't break during the cutover.
     */
    @Deprecated
    @Transactional
    public DeviceResponse powerOnDevice(UUID deviceId) {
        return setInService(deviceId, true);
    }

    /**
     * @deprecated Use {@link #setInService(UUID, boolean)} with
     * {@code inService = false}.
     */
    @Deprecated
    @Transactional
    public DeviceResponse powerOffDevice(UUID deviceId) {
        return setInService(deviceId, false);
    }

    // ====================================================================
    // V54 — TRIAGE-ZONE MONITOR FLAG
    // ====================================================================

    /**
     * Mark / unmark a device as the triage-zone monitor. Only triage-flagged
     * devices appear in the triage form's "Pull from Monitor" picker.
     */
    @Transactional
    public DeviceResponse setTriageMonitor(UUID deviceId, boolean triageMonitor) {
        IoTDevice device = findDeviceOrThrow(deviceId);
        if (triageMonitor == device.isTriageMonitor()) {
            return IoTMapper.toResponse(device);
        }
        device.setTriageMonitor(triageMonitor);
        device = deviceRepository.save(device);
        log.info("Device {} (serial {}) triageMonitor set to {}",
                device.getDeviceName(), device.getSerialNumber(), triageMonitor);
        publishDeviceStatus(device);
        return IoTMapper.toResponse(device);
    }

    /**
     * List the hospital's triage-zone monitors (triageMonitor=true AND inService=true).
     * The triage form calls this once to populate the monitor-picker pill.
     */
    @Transactional(readOnly = true)
    public List<DeviceResponse> getTriageMonitors(UUID hospitalId) {
        return deviceRepository.findTriageMonitors(hospitalId).stream()
                .map(IoTMapper::toResponse)
                .toList();
    }

    // ====================================================================
    // DEVICE AUTHENTICATION
    // ====================================================================

    /**
     * Authenticate a device by its API key. Returns the device entity or throws.
     * Called on every incoming data stream request.
     */
    public IoTDevice authenticateDevice(String apiKey) {
        return deviceRepository.findByApiKeyAndIsActiveTrue(apiKey)
                .orElseThrow(() -> new ResourceNotFoundException("IoTDevice", "apiKey", "***"));
    }

    // ====================================================================
    // HEARTBEAT
    // ====================================================================

    /**
     * Process a heartbeat from a device. Updates last seen timestamp and status.
     *
     * Also performs self-healing auto-pairing: if the device is assigned to a bed
     * that currently holds a patient but no active DeviceSession exists, we open
     * one. This covers the case where the device was OFFLINE at the moment of
     * patient placement (so BedService.autoStartSessionForBed bailed out) and
     * comes online later.
     */
    @Transactional
    public void processHeartbeat(IoTDevice device, String ipAddress) {
        // Re-fetch to get the latest version (avoids optimistic lock conflicts
        // when the built-in simulator or another source updates the same device)
        IoTDevice fresh = deviceRepository.findById(device.getId())
                .orElse(device);

        fresh.setLastHeartbeatAt(Instant.now());
        if (ipAddress != null) {
            fresh.setIpAddress(ipAddress);
        }

        boolean cameOnline = false;
        // If device was REGISTERED or OFFLINE, bring it ONLINE
        if (fresh.getStatus() == DeviceStatus.REGISTERED
                || fresh.getStatus() == DeviceStatus.OFFLINE) {
            fresh.setStatus(DeviceStatus.ONLINE);
            cameOnline = true;
            log.info("Device {} came online", fresh.getSerialNumber());
        }

        fresh = deviceRepository.save(fresh);

        // Self-healing: if this device has a bed with an occupied visit but no
        // active session, open one now. This handles the case where a patient
        // was placed in the bed while the device was offline.
        if (cameOnline || fresh.getStatus() == DeviceStatus.ONLINE) {
            try {
                autoPairIfMissingSession(fresh);
            } catch (Exception e) {
                log.warn("Self-healing auto-pair failed for device {}: {}",
                        fresh.getSerialNumber(), e.getMessage());
            }
        }

        // Notify frontend of device status change
        publishDeviceStatus(fresh);
    }

    /**
     * If the device has an assigned bed holding an active visit but no current
     * DeviceSession, open one and transition the device to MONITORING. No-op
     * if any precondition is missing.
     */
    @Transactional
    protected void autoPairIfMissingSession(IoTDevice device) {
        com.smartTriage.smartTriage_server.module.bed.entity.Bed bed = device.getAssignedBed();
        if (bed == null) {
            return; // portable device — nothing to pair
        }
        Visit visit = bed.getCurrentVisit();
        if (visit == null) {
            return; // bed has no patient
        }

        // Already paired?
        boolean alreadyPaired = sessionRepository
                .findByDeviceIdAndSessionActiveTrueAndIsActiveTrue(device.getId())
                .isPresent();
        if (alreadyPaired) {
            return;
        }

        // Close any stray session on the visit from a different device
        sessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(visit.getId())
                .ifPresent(stale -> {
                    log.warn("Auto-closing prior session {} on visit {} before heartbeat auto-pair",
                            stale.getId(), visit.getVisitNumber());
                    stale.endSession("System", "Auto-closed: heartbeat re-pairing");
                    sessionRepository.save(stale);
                });

        DeviceSession session = DeviceSession.builder()
                .device(device)
                .visit(visit)
                .startedAt(Instant.now())
                .sessionActive(true)
                .startedByName("Heartbeat auto-pair")
                .build();
        sessionRepository.save(session);

        IoTDevice fresh = deviceRepository.findById(device.getId()).orElse(device);
        fresh.setStatus(DeviceStatus.MONITORING);
        deviceRepository.save(fresh);

        log.info("Self-healing auto-pair: device {} → visit {} via bed {}",
                fresh.getSerialNumber(), visit.getVisitNumber(), bed.getCode());
    }

    // ====================================================================
    // MONITORING SESSION MANAGEMENT
    // ====================================================================

    /**
     * Start a monitoring session — link a device to a patient's visit.
     * Only one active session per device is allowed.
     */
    @Transactional
    public DeviceSessionResponse startMonitoring(StartMonitoringRequest request) {
        IoTDevice device = findDeviceOrThrow(request.getDeviceId());
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());

        // Validate: device must be ONLINE (not already MONITORING or OFFLINE)
        if (device.getStatus() != DeviceStatus.ONLINE
                && device.getStatus() != DeviceStatus.REGISTERED) {
            throw new IllegalStateException(
                    "Device " + device.getSerialNumber() + " is not available for monitoring. " +
                            "Current status: " + device.getStatus());
        }

        // Validate: no active session on this device
        // If device is ONLINE but has a stale active session (e.g., after server
        // restart
        // or simulator toggle), auto-close it instead of blocking the new assignment.
        sessionRepository.findByDeviceIdAndSessionActiveTrueAndIsActiveTrue(device.getId())
                .ifPresent(staleSession -> {
                    if (device.getStatus() == DeviceStatus.ONLINE
                            || device.getStatus() == DeviceStatus.REGISTERED) {
                        // Device was reset/restarted — close the orphaned session
                        log.warn("Auto-closing stale session {} for device {} (device status: {})",
                                staleSession.getId(), device.getSerialNumber(), device.getStatus());
                        staleSession.setSessionActive(false);
                        staleSession.setEndedAt(Instant.now());
                        staleSession.setEndReason("Auto-closed: device was reset");
                        sessionRepository.save(staleSession);
                    } else {
                        throw new IllegalStateException(
                                "Device " + device.getSerialNumber() +
                                        " already has an active monitoring session for visit " +
                                        staleSession.getVisit().getVisitNumber());
                    }
                });

        // Validate: no active session on this visit from another device
        sessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(visit.getId())
                .ifPresent(s -> {
                    throw new IllegalStateException(
                            "Visit " + visit.getVisitNumber() +
                                    " is already being monitored by device " +
                                    s.getDevice().getSerialNumber());
                });

        // Create session
        DeviceSession session = DeviceSession.builder()
                .device(device)
                .visit(visit)
                .startedAt(Instant.now())
                .sessionActive(true)
                .startedByName(request.getStartedByName())
                .build();

        session = sessionRepository.save(session);

        // Re-fetch device to get latest version (avoids conflict with concurrent
        // heartbeats)
        IoTDevice freshDevice = deviceRepository.findByIdAndIsActiveTrue(device.getId())
                .orElse(device);
        freshDevice.setStatus(DeviceStatus.MONITORING);
        deviceRepository.save(freshDevice);

        log.info("Monitoring started: Device {} -> Visit {} (Session: {})",
                freshDevice.getSerialNumber(), visit.getVisitNumber(), session.getId());

        // Notify frontend of device status change
        publishDeviceStatus(freshDevice);

        return IoTMapper.toResponse(session);
    }

    /**
     * Stop a monitoring session.
     */
    @Transactional
    public DeviceSessionResponse stopMonitoring(UUID sessionId, String endedByName, String reason) {
        DeviceSession session = sessionRepository.findByIdAndIsActiveTrue(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceSession", "id", sessionId));

        if (!session.isSessionActive()) {
            throw new IllegalStateException("Session is already ended");
        }

        session.endSession(endedByName, reason != null ? reason : "Manual stop");
        session = sessionRepository.save(session);

        // Re-fetch device to avoid version conflict with concurrent heartbeats,
        // then return device to ONLINE
        IoTDevice device = session.getDevice();
        IoTDevice freshDevice = deviceRepository.findByIdAndIsActiveTrue(device.getId())
                .orElse(device);
        freshDevice.setStatus(DeviceStatus.ONLINE);
        deviceRepository.save(freshDevice);

        log.info("Monitoring stopped: Device {} | Visit {} | Reason: {} | " +
                "Readings: {} (rejected: {}) | Alerts: {} | Retriages: {}",
                freshDevice.getSerialNumber(), session.getVisit().getVisitNumber(),
                reason, session.getTotalReadings(), session.getRejectedReadings(),
                session.getAlertsGenerated(), session.getRetriagesTriggered());

        // Notify frontend of device → ONLINE status
        publishDeviceStatus(freshDevice);

        return IoTMapper.toResponse(session);
    }

    /**
     * V54 — Stop any active monitoring session for this visit that's bound
     * to a triage-zone monitor. Called by TriageService after a successful
     * triage submission so we don't leak sessions started by the "Pull from
     * Monitor" flow.
     *
     * Bedside-monitor sessions (devices flagged as triage_monitor = false,
     * e.g. those auto-paired via assignedBed) are intentionally left running
     * — those continue through the patient's stay.
     */
    @Transactional
    public void stopTriageMonitorSessionForVisit(UUID visitId, String endedByName) {
        sessionRepository.findByVisitIdAndSessionActiveTrueAndIsActiveTrue(visitId)
                .ifPresent(session -> {
                    IoTDevice device = session.getDevice();
                    if (device != null && device.isTriageMonitor()) {
                        try {
                            stopMonitoring(session.getId(),
                                    endedByName != null ? endedByName : "System",
                                    "Triage complete");
                            log.info("V54 — triage-monitor session {} stopped for visit {} after triage submit",
                                    session.getId(), visitId);
                        } catch (Exception e) {
                            // Don't let a session-stop failure roll back the triage record.
                            log.warn("V54 — failed to stop triage-monitor session {} for visit {}: {}",
                                    session.getId(), visitId, e.getMessage());
                        }
                    }
                });
    }

    /**
     * Stop monitoring by device ID (used when device disconnects).
     */
    @Transactional
    public void stopMonitoringForDevice(UUID deviceId, String reason) {
        sessionRepository.findByDeviceIdAndSessionActiveTrueAndIsActiveTrue(deviceId)
                .ifPresent(session -> {
                    session.endSession("System", reason);
                    sessionRepository.save(session);
                    log.info("Session auto-closed for device {} — {}",
                            session.getDevice().getSerialNumber(), reason);
                });
    }

    // ====================================================================
    // DEVICE QUERIES
    // ====================================================================

    public DeviceResponse getDevice(UUID deviceId) {
        IoTDevice device = findDeviceOrThrow(deviceId);
        UUID activeVisitId = sessionRepository
                .findByDeviceIdAndSessionActiveTrueAndIsActiveTrue(deviceId)
                .map(s -> s.getVisit().getId())
                .orElse(null);
        return IoTMapper.toResponse(device, activeVisitId);
    }

    public Page<DeviceResponse> getDevicesByHospital(UUID hospitalId, Pageable pageable) {
        Page<IoTDevice> devicePage = deviceRepository
                .findByHospitalIdAndIsActiveTrueOrderByDeviceNameAsc(hospitalId, pageable);

        // Batch-fetch all active sessions for this hospital so we can populate
        // activeVisitId
        List<DeviceSession> activeSessions = sessionRepository
                .findByDeviceHospitalIdAndSessionActiveTrueAndIsActiveTrue(hospitalId);

        // Build a quick lookup: deviceId → visitId
        java.util.Map<UUID, UUID> deviceToVisit = new java.util.HashMap<>();
        for (DeviceSession s : activeSessions) {
            deviceToVisit.put(s.getDevice().getId(), s.getVisit().getId());
        }

        return devicePage.map(device -> IoTMapper.toResponse(device, deviceToVisit.get(device.getId())));
    }

    public List<DeviceResponse> getAvailableDevices(UUID hospitalId) {
        // V53 — out-of-service devices are not assignable. Filter
        // here rather than in the repository query so the JPQL stays
        // readable; this list is small (active monitors at a hospital).
        return deviceRepository.findAvailableDevices(hospitalId)
                .stream()
                .filter(IoTDevice::isInService)
                .map(IoTMapper::toResponse)
                .toList();
    }

    public List<DeviceSessionResponse> getActiveSessions(UUID hospitalId) {
        return sessionRepository
                .findByDeviceHospitalIdAndSessionActiveTrueAndIsActiveTrue(hospitalId)
                .stream()
                .map(IoTMapper::toResponse)
                .toList();
    }

    public DeviceSessionResponse getSession(UUID sessionId) {
        DeviceSession session = sessionRepository.findByIdAndIsActiveTrue(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceSession", "id", sessionId));
        return IoTMapper.toResponse(session);
    }

    public Page<DeviceSessionResponse> getSessionHistory(UUID visitId, Pageable pageable) {
        return sessionRepository
                .findByVisitIdAndIsActiveTrueOrderByStartedAtDesc(visitId, pageable)
                .map(IoTMapper::toResponse);
    }

    /**
     * Mark a device as offline (called by the heartbeat scheduler).
     */
    @Transactional
    public void markDeviceOffline(IoTDevice device) {
        device.setStatus(DeviceStatus.OFFLINE);
        deviceRepository.save(device);
        log.warn("Device {} marked OFFLINE (heartbeat timeout)", device.getSerialNumber());

        // Notify frontend of device → OFFLINE status
        publishDeviceStatus(device);
    }

    /**
     * Get all devices that have missed their heartbeat deadline.
     */
    public List<IoTDevice> findStaleDevices() {
        // Each device has its own timeout, but we use a reasonable global cutoff
        Instant cutoff = Instant.now().minusSeconds(60);
        return deviceRepository.findStaleDevices(cutoff);
    }

    // ====================================================================
    // INTERNAL
    // ====================================================================

    public IoTDevice findDeviceOrThrow(UUID id) {
        return deviceRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("IoTDevice", "id", id));
    }

    public DeviceSession findActiveSessionForDevice(UUID deviceId) {
        return sessionRepository
                .findByDeviceIdAndSessionActiveTrueAndIsActiveTrue(deviceId)
                .orElse(null);
    }

    /**
     * Publish device status change to the WebSocket topic for the hospital.
     *
     * <p><b>Critical timing fix:</b> Defer the publish to <em>after</em>
     * the surrounding transaction commits. Firing synchronously from
     * inside the TX let frontend clients re-fetch device state via HTTP
     * before the TX's writes were visible, so they'd see stale data —
     * a device that the just-completed call had set to {@code MONITORING}
     * would still be reported as {@code ONLINE}, breaking the "is this
     * device streaming?" check that drives the Monitoring page's
     * live/Demo indicator.
     */
    private void publishDeviceStatus(IoTDevice device) {
        // Snapshot every field we need inside the TX while the entity is
        // attached. The actual publish reads from this map only.
        final java.util.UUID hospitalId;
        // Loose-typed to match RealTimeEventPublisher.publishDeviceStatusChange(Map<String,Object>).
        final java.util.Map<String, Object> payload;
        try {
            hospitalId = device.getHospital().getId();
            payload = java.util.Map.of(
                    "deviceId", device.getId().toString(),
                    "serialNumber", device.getSerialNumber(),
                    "deviceName", device.getDeviceName(),
                    "status", device.getStatus().name(),
                    "timestamp", Instant.now().toString());
        } catch (Exception e) {
            log.warn("Failed to build device status payload: {}", e.getMessage());
            return;
        }

        Runnable fire = () -> {
            try {
                eventPublisher.publishDeviceStatusChange(hospitalId, payload);
            } catch (Exception e) {
                log.warn("Failed to publish device status change: {}", e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() { fire.run(); }
                    });
        } else {
            fire.run();
        }
    }

    /**
     * Generate a cryptographically secure API key.
     * Format: "st_dev_" + 48 random bytes base64url encoded
     */
    private String generateApiKey() {
        byte[] randomBytes = new byte[48];
        SECURE_RANDOM.nextBytes(randomBytes);
        return "st_dev_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
