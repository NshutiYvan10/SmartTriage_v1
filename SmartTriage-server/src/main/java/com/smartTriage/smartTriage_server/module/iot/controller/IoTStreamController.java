package com.smartTriage.smartTriage_server.module.iot.controller;

import com.smartTriage.smartTriage_server.module.iot.dto.DeviceAckResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceVitalPayload;
import com.smartTriage.smartTriage_server.module.iot.engine.ContinuousMonitoringEngine;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.service.DeviceService;
import com.smartTriage.smartTriage_server.module.iot.service.VitalStreamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * IoTStreamController — data ingestion endpoint for IoT devices.
 *
 * This endpoint is called by ESP32 devices at their configured data interval
 * (typically every 5 seconds). Devices authenticate using their pre-shared API key
 * in the X-Device-API-Key header.
 *
 * Endpoint flow:
 *   1. Authenticate device via API key
 *   2. Resolve active monitoring session
 *   3. Validate and persist vital data (VitalStreamService)
 *   4. Run AI deterioration analysis (ContinuousMonitoringEngine)
 *   5. Return acknowledgment with optional commands
 *
 * Performance: target < 100ms per request (5s interval × N devices).
 *
 * Note: This endpoint is excluded from JWT auth in SecurityConfig.
 * It uses API key authentication instead (suitable for embedded devices).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/iot/stream")
@RequiredArgsConstructor
public class IoTStreamController {

    private final DeviceService deviceService;
    private final VitalStreamService vitalStreamService;
    private final ContinuousMonitoringEngine monitoringEngine;

    /**
     * Ingest vital data from an IoT device.
     *
     * ESP32 devices call this endpoint every N seconds with their latest readings.
     * The API key header authenticates the device. The serial number in the payload
     * is cross-validated against the authenticated device.
     *
     * @param apiKey  the device's pre-shared API key (from X-Device-API-Key header)
     * @param payload the vital data payload
     * @return DeviceAckResponse with acceptance status and optional commands
     */
    @PostMapping("/ingest")
    public ResponseEntity<DeviceAckResponse> ingestVitals(
            @RequestHeader("X-Device-API-Key") String apiKey,
            @Valid @RequestBody DeviceVitalPayload payload) {

        // Step 1: Authenticate device
        IoTDevice device;
        try {
            device = deviceService.authenticateDevice(apiKey);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(
                    DeviceAckResponse.builder()
                            .accepted(false)
                            .rejectionReason("Device authentication failed")
                            .serverTimestamp(Instant.now().toEpochMilli())
                            .build());
        }

        // Step 2: Cross-validate serial number
        if (!device.getSerialNumber().equals(payload.getSerialNumber())) {
            log.warn("Serial number mismatch: API key device={}, payload={}",
                    device.getSerialNumber(), payload.getSerialNumber());
            return ResponseEntity.badRequest().body(
                    DeviceAckResponse.builder()
                            .accepted(false)
                            .rejectionReason("Serial number mismatch")
                            .serverTimestamp(Instant.now().toEpochMilli())
                            .build());
        }

        // Step 3: Update heartbeat
        deviceService.processHeartbeat(device, null);

        // Step 4: Resolve active session
        DeviceSession session = deviceService.findActiveSessionForDevice(device.getId());

        // Step 5: Ingest and validate
        DeviceAckResponse ack = vitalStreamService.ingestVitals(payload, device, session);

        // Step 6: Run AI monitoring (only on valid readings with active session)
        if (ack.isAccepted() && session != null) {
            try {
                monitoringEngine.analyseAndRespond(
                        session.getVisit().getId(), session);
            } catch (Exception e) {
                // Monitoring engine failure should NOT block data ingestion
                log.error("Monitoring engine error for device {}: {}",
                        device.getSerialNumber(), e.getMessage(), e);
            }
        }

        return ResponseEntity.ok(ack);
    }

    /**
     * Heartbeat endpoint — lightweight keepalive from devices.
     * Devices call this when not actively sending vital data.
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<DeviceAckResponse> heartbeat(
            @RequestHeader("X-Device-API-Key") String apiKey,
            @RequestHeader(value = "X-Device-IP", required = false) String ipAddress) {

        IoTDevice device;
        try {
            device = deviceService.authenticateDevice(apiKey);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(
                    DeviceAckResponse.builder()
                            .accepted(false)
                            .rejectionReason("Device authentication failed")
                            .serverTimestamp(Instant.now().toEpochMilli())
                            .build());
        }

        deviceService.processHeartbeat(device, ipAddress);

        return ResponseEntity.ok(DeviceAckResponse.builder()
                .accepted(true)
                .serverTimestamp(Instant.now().toEpochMilli())
                .requestedIntervalSeconds(device.getDataIntervalSeconds())
                .build());
    }
}
