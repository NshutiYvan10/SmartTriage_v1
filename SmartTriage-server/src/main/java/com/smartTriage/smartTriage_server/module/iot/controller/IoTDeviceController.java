package com.smartTriage.smartTriage_server.module.iot.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.*;
import com.smartTriage.smartTriage_server.module.iot.service.DeviceService;
import com.smartTriage.smartTriage_server.module.iot.service.VitalStreamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * IoTDeviceController — REST endpoints for device management and monitoring
 * sessions.
 *
 * Endpoints:
 * POST /api/v1/iot/devices — register a new device
 * GET /api/v1/iot/devices/{id} — get device details
 * GET /api/v1/iot/devices/hospital/{id} — list devices by hospital
 * GET /api/v1/iot/devices/available/{id} — available devices for a hospital
 *
 * POST /api/v1/iot/monitoring/start — start monitoring session
 * POST /api/v1/iot/monitoring/stop/{id} — stop monitoring session
 * GET /api/v1/iot/monitoring/active/{id} — active sessions for hospital
 * GET /api/v1/iot/monitoring/session/{id} — session details
 * GET /api/v1/iot/monitoring/history/{id} — session history for visit
 *
 * GET /api/v1/iot/stream/latest/{visitId} — latest reading
 * GET /api/v1/iot/stream/recent/{visitId} — recent readings
 * GET /api/v1/iot/stream/history/{visitId} — paginated stream history
 *
 * Security: All endpoints require authentication (staff users).
 * Device-facing endpoints use API key auth (see IoTStreamController).
 */
@RestController
@RequestMapping("/api/v1/iot")
@RequiredArgsConstructor
public class IoTDeviceController {

    private final DeviceService deviceService;
    private final VitalStreamService vitalStreamService;

    // ====================================================================
    // DEVICE MANAGEMENT
    // ====================================================================

    @PostMapping("/devices")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceResponse>> registerDevice(
            @Valid @RequestBody RegisterDeviceRequest request) {
        DeviceResponse response = deviceService.registerDevice(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Device registered successfully", response));
    }

    @GetMapping("/devices/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DeviceResponse>> getDevice(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getDevice(id)));
    }

    @GetMapping("/devices/hospital/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<DeviceResponse>>> getDevicesByHospital(
            @PathVariable UUID hospitalId, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getDevicesByHospital(hospitalId, pageable)));
    }

    @GetMapping("/devices/available/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getAvailableDevices(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getAvailableDevices(hospitalId)));
    }

    @PostMapping("/devices/{id}/power-on")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceResponse>> powerOnDevice(@PathVariable UUID id) {
        DeviceResponse response = deviceService.powerOnDevice(id);
        return ResponseEntity.ok(ApiResponse.success("Device powered on", response));
    }

    @PostMapping("/devices/{id}/power-off")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceResponse>> powerOffDevice(@PathVariable UUID id) {
        DeviceResponse response = deviceService.powerOffDevice(id);
        return ResponseEntity.ok(ApiResponse.success("Device powered off", response));
    }

    // ====================================================================
    // MONITORING SESSION MANAGEMENT
    // ====================================================================

    @PostMapping("/monitoring/start")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<DeviceSessionResponse>> startMonitoring(
            @Valid @RequestBody StartMonitoringRequest request) {
        DeviceSessionResponse response = deviceService.startMonitoring(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Monitoring started", response));
    }

    @PostMapping("/monitoring/stop/{sessionId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<DeviceSessionResponse>> stopMonitoring(
            @PathVariable UUID sessionId,
            @RequestParam(required = false) String endedByName,
            @RequestParam(required = false) String reason) {
        DeviceSessionResponse response = deviceService.stopMonitoring(sessionId, endedByName, reason);
        return ResponseEntity.ok(ApiResponse.success("Monitoring stopped", response));
    }

    @GetMapping("/monitoring/active/{hospitalId}")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<DeviceSessionResponse>>> getActiveSessions(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getActiveSessions(hospitalId)));
    }

    @GetMapping("/monitoring/session/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DeviceSessionResponse>> getSession(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getSession(sessionId)));
    }

    @GetMapping("/monitoring/history/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<DeviceSessionResponse>>> getSessionHistory(
            @PathVariable UUID visitId, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getSessionHistory(visitId, pageable)));
    }

    // ====================================================================
    // VITAL STREAM DATA — every read is gated against the visit's hospital
    // ====================================================================

    @GetMapping("/stream/latest/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<VitalStreamResponse>> getLatestReading(@PathVariable UUID visitId) {
        VitalStreamResponse response = vitalStreamService.getLatestReading(visitId);
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/stream/recent/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<VitalStreamResponse>>> getRecentReadings(
            @PathVariable UUID visitId,
            @RequestParam(defaultValue = "60") int count) {
        return ResponseEntity.ok(ApiResponse.success(vitalStreamService.getRecentReadings(visitId, count)));
    }

    @GetMapping("/stream/history/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<VitalStreamResponse>>> getStreamHistory(
            @PathVariable UUID visitId, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(vitalStreamService.getStreamHistory(visitId, pageable)));
    }

    @GetMapping("/stream/session/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Page<VitalStreamResponse>>> getSessionStream(
            @PathVariable UUID sessionId, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(vitalStreamService.getSessionStreamHistory(sessionId, pageable)));
    }
}
