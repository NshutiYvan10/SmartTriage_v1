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
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getAlertsForVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService.getAlertsForVisit(visitId, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/unacknowledged")
    @PreAuthorize("@clinicalAuthz.canReadHospitalAlerts(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getUnacknowledgedAlerts(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService.getUnacknowledgedAlerts(hospitalId, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/all")
    @PreAuthorize("@clinicalAuthz.canReadHospitalAlerts(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getAllAlerts(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 100) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService.getAllAlerts(hospitalId, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/critical")
    @PreAuthorize("@clinicalAuthz.canReadHospitalAlerts(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getCriticalAlerts(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService.getCriticalAlerts(hospitalId, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Phase 14 Override Audit dashboard — server-side filter for
     * MEDICATION_SAFETY_WARNING alerts within a date window. The dashboard
     * was previously fetching every alert and filtering client-side; this
     * lets it scale past a few hundred overrides per hospital.
     *
     * @param range one of "24h", "7d", "30d", "all" (case-insensitive).
     *              Unknown values are treated as "all" rather than
     *              rejected — a stale link shouldn't take the dashboard
     *              down.
     */
    @GetMapping("/hospital/{hospitalId}/safety-overrides")
    // The Override Audit is a governance/forensic surface — gate it with the
    // dedicated audit authority, NOT the operational canReadHospitalAlerts
    // (which denies HOSPITAL_ADMIN and required a clinical shift badge, so the
    // page rendered blank for its own intended audience).
    @PreAuthorize("@clinicalAuthz.canAuditSafetyOverrides(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<ClinicalAlertResponse>>> getSafetyOverrides(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false, defaultValue = "all") String range,
            @PageableDefault(size = 200) Pageable pageable) {
        Page<ClinicalAlertResponse> response = clinicalAlertService
                .getSafetyOverrides(hospitalId, range, pageable)
                .map(ClinicalAlertMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{alertId}/acknowledge")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ClinicalAlertResponse>> acknowledgeAlert(
            @PathVariable UUID alertId,
            // B5 — optional acknowledge/dismiss comment. Previously the
            // dialog captured this but it was never sent or stored.
            @RequestParam(required = false) String note) {
        ClinicalAlert alert = clinicalAlertService.acknowledgeAlert(alertId, note);
        return ResponseEntity.ok(ApiResponse.success("Alert acknowledged", ClinicalAlertMapper.toResponse(alert)));
    }

    /**
     * Acknowledge / sign off a medication-safety OVERRIDE alert from the
     * Override Audit dashboard. Separate from the generic acknowledge so the
     * governance audience (admin, safety officer, doctor, charge nurse) can mark
     * an override reviewed — the authz loads the alert, confirms it is an
     * override row, and applies canAuditSafetyOverrides for its hospital, so it
     * can never be used to acknowledge an operational clinical alert.
     */
    @PatchMapping("/{alertId}/safety-override/acknowledge")
    @PreAuthorize("@clinicalAuthz.canAcknowledgeSafetyOverride(authentication, #alertId)")
    public ResponseEntity<ApiResponse<ClinicalAlertResponse>> acknowledgeSafetyOverride(
            @PathVariable UUID alertId,
            @RequestParam(required = false) String note) {
        ClinicalAlert alert = clinicalAlertService.acknowledgeAlert(alertId, note);
        return ResponseEntity.ok(ApiResponse.success("Override acknowledged", ClinicalAlertMapper.toResponse(alert)));
    }

    // ====================================================================
    // ZONE-AWARE ENDPOINTS
    // ====================================================================

    /**
     * Get unacknowledged alerts for a specific ED zone. Allowed for
     * cross-zone actors (admins, shift-lead, Charge Nurse) and for any
     * clinician whose own active assignment is on this zone. Without
     * this gate a doctor on GENERAL could request RESUS alerts by
     * URL-tampering — the same trap the hospital-wide endpoint had.
     */
    @GetMapping("/hospital/{hospitalId}/zone/{zone}")
    @PreAuthorize("@clinicalAuthz.canReadHospitalAlerts(authentication, #hospitalId) "
            + "or @visitService.callerIsAssignedToZone(authentication, #hospitalId, #zone)")
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
     * Get alerts targeted at a specific doctor. Restricted to the doctor
     * themselves (reading their own alert queue) and SUPER_ADMIN.
     * HOSPITAL_ADMIN is intentionally excluded — clinical alerts are not an
     * administrator surface. Without the self check any DOCTOR could fetch
     * any other doctor's alert queue cross-hospital.
     */
    @GetMapping("/doctor/{doctorId}")
    @PreAuthorize("hasRole('SUPER_ADMIN') "
            + "or (hasRole('DOCTOR') "
            + "    and @clinicalAuthz.canAccessUser(authentication, #doctorId))")
    public ResponseEntity<ApiResponse<List<ClinicalAlertResponse>>> getDoctorAlerts(
            @PathVariable UUID doctorId) {
        List<ClinicalAlertResponse> alerts = clinicalAlertService.getAlertsForDoctor(doctorId)
                .stream()
                .map(ClinicalAlertMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(alerts));
    }
}
