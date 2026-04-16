package com.smartTriage.smartTriage_server.module.alert.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.alert.entity.ClinicalAlert;
import com.smartTriage.smartTriage_server.module.alert.mapper.ClinicalAlertMapper;
import com.smartTriage.smartTriage_server.module.alert.service.ClinicalAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Clinical alerts endpoints — the alert queue for the ED dashboard.
 * Includes zone-aware queries for zone doctors and charge nurse dashboard.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class ClinicalAlertController {

    private final ClinicalAlertService clinicalAlertService;

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getAlertsForVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService.getAlertsForVisit(visitId, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/unacknowledged")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getUnacknowledgedAlerts(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService.getUnacknowledgedAlerts(hospitalId, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/all")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getAllAlerts(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 100) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService.getAllAlerts(hospitalId, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/critical")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getCriticalAlerts(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService.getCriticalAlerts(hospitalId, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{alertId}/acknowledge")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalAlertResponse>> acknowledgeAlert(@PathVariable UUID alertId) {
        ClinicalAlert alert = clinicalAlertService.acknowledgeAlert(alertId);
        return ResponseEntity.ok(ApiResponse.success("Alert acknowledged", ClinicalAlertMapper.toResponse(alert)));
    }

    // ====================================================================
    // ZONE-AWARE ENDPOINTS
    // ====================================================================

    /**
     * Get unacknowledged alerts for a specific ED zone — for zone doctor dashboard.
     */
    @GetMapping("/hospital/{hospitalId}/zone/{zone}")
    public ResponseEntity<ApiResponse<List<ClinicalAlertResponse>>> getZoneAlerts(
            @PathVariable UUID hospitalId,
            @PathVariable EdZone zone) {
        List<ClinicalAlertResponse> alerts = clinicalAlertService.getUnacknowledgedAlertsByZone(hospitalId, zone)
                .stream()
                .map(ClinicalAlertMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(alerts));
    }

    /**
     * Get alerts targeted at a specific doctor.
     */
    @GetMapping("/doctor/{doctorId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<List<ClinicalAlertResponse>>> getDoctorAlerts(
            @PathVariable UUID doctorId) {
        List<ClinicalAlertResponse> alerts = clinicalAlertService.getAlertsForDoctor(doctorId)
                .stream()
                .map(ClinicalAlertMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(alerts));
    }
}
