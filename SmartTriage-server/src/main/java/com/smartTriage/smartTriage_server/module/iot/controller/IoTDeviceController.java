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

    /**
     * V53 — admin toggles a device's inventory status.
     * Body: { "inService": true | false }
     */
    @org.springframework.web.bind.annotation.PatchMapping("/devices/{id}/service-status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceResponse>> setServiceStatus(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Boolean> body) {
        boolean inService = Boolean.TRUE.equals(body.get("inService"));
        DeviceResponse response = deviceService.setInService(id, inService);
        return ResponseEntity.ok(ApiResponse.success(
                inService ? "Device returned to service" : "Device taken out of service",
                response));
    }

    /**
     * V54 — admin toggles a device's triage-zone flag.
     * Body: { "triageMonitor": true | false }
     */
    @org.springframework.web.bind.annotation.PatchMapping("/devices/{id}/triage-monitor")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceResponse>> setTriageMonitor(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Boolean> body) {
        boolean triageMonitor = Boolean.TRUE.equals(body.get("triageMonitor"));
        DeviceResponse response = deviceService.setTriageMonitor(id, triageMonitor);
        return ResponseEntity.ok(ApiResponse.success(
                triageMonitor ? "Device marked as triage-zone monitor" : "Device unmarked as triage-zone monitor",
                response));
    }

    /**
     * V54 — list the hospital's triage-zone monitors (in service + flagged).
     * Called by the triage form to populate the "Pull from Monitor" picker.
     */
    @GetMapping("/devices/triage-monitors/{hospitalId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getTriageMonitors(@PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getTriageMonitors(hospitalId)));
    }

    /** @deprecated retained as a thin alias for legacy clients; use /service-status. */
    @Deprecated
    @PostMapping("/devices/{id}/power-on")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceResponse>> powerOnDevice(@PathVariable UUID id) {
        DeviceResponse response = deviceService.setInService(id, true);
        return ResponseEntity.ok(ApiResponse.success("Device returned to service", response));
    }

    /** @deprecated retained as a thin alias for legacy clients; use /service-status. */
    @Deprecated
    @PostMapping("/devices/{id}/power-off")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')")
    public ResponseEntity<ApiResponse<DeviceResponse>> powerOffDevice(@PathVariable UUID id) {
        DeviceResponse response = deviceService.setInService(id, false);
        return ResponseEntity.ok(ApiResponse.success("Device taken out of service", response));
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

    /**
     * Clinician-facing convenience endpoint: start monitoring for a
     * visit without naming the device. The backend walks
     * visit → currentBed → assignedDevice and opens a session. Used
     * by the "Start Monitoring" inline button on Constant Monitoring.
     */
    @PostMapping("/monitoring/start-for-visit/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<DeviceSessionResponse>> startMonitoringForVisit(
            @PathVariable UUID visitId,
            @RequestParam(required = false) String startedByName) {
        DeviceSessionResponse response = deviceService.startMonitoringForVisit(visitId, startedByName);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Monitoring started", response));
    }

    /**
     * Returns the current monitoring session for a visit, or null when
     * monitoring has not been started yet (frontend renders the
     * "Awaiting Start" pill in that case).
     */
    @GetMapping("/monitoring/active-for-visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<DeviceSessionResponse>> getActiveSessionForVisit(
            @PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(deviceService.getActiveSessionForVisit(visitId)));
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
