package com.smartTriage.smartTriage_server.module.vital.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.vital.dto.RecordVitalsRequest;
import com.smartTriage.smartTriage_server.module.vital.dto.VitalSignsResponse;
import com.smartTriage.smartTriage_server.module.vital.service.VitalSignsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Vital signs capture endpoints.
 * Handles both manual entry and IoT device integration.
 */
@RestController
@RequestMapping("/api/v1/vitals")
@RequiredArgsConstructor
public class VitalSignsController {

    private final VitalSignsService vitalSignsService;

    /**
     * RBAC fix — vitals write is zone-scoped. The caller's current shift
     * zone must match the visit's currentEdZone, OR the caller must have
     * cross-zone authority (Charge Nurse / shift-lead), OR be today's
     * Triage Nurse writing to a pre-triage visit. PARAMEDIC retains
     * write access via callerCanWriteToVisit's operational-role branch.
     * SUPER_ADMIN / HOSPITAL_ADMIN no longer have vitals write — admins
     * are not clinical actors.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('DOCTOR', 'NURSE', 'PARAMEDIC') "
            + "and @clinicalAuthz.callerCanWriteToVisit(authentication, #request.visitId)")
    public ResponseEntity<ApiResponse<VitalSignsResponse>> recordVitals(
            @Valid @RequestBody RecordVitalsRequest request) {
        VitalSignsResponse response = vitalSignsService.recordVitals(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Vital signs recorded", response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<VitalSignsResponse>>> getVitalsByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<VitalSignsResponse> response = vitalSignsService.getVitalsByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/latest")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<VitalSignsResponse>> getLatestVitals(
            @PathVariable UUID visitId) {
        VitalSignsResponse response = vitalSignsService.getLatestVitals(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
