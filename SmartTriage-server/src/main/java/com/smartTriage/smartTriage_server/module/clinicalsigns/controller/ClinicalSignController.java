package com.smartTriage.smartTriage_server.module.clinicalsigns.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.clinicalsigns.dto.ClinicalSignEventResponse;
import com.smartTriage.smartTriage_server.module.clinicalsigns.dto.RecordClinicalSignsBatchRequest;
import com.smartTriage.smartTriage_server.module.clinicalsigns.service.ClinicalSignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Clinical-sign event endpoints.
 *
 *   GET  /api/v1/clinical-signs/visit/{visitId}          — full event history
 *   GET  /api/v1/clinical-signs/visit/{visitId}/current  — latest per-sign state
 *   GET  /api/v1/clinical-signs/visit/{visitId}/sign/{signCode} — per-sign timeline
 *   POST /api/v1/clinical-signs                          — batch record updates
 *
 * Recording is gated to clinical roles (DOCTOR, NURSE,
 * SUPER_ADMIN). Reading is open to the same set plus HOSPITAL_ADMIN
 * for chart audit. REGISTRAR / PARAMEDIC / LAB_TECHNICIAN are excluded —
 * clinical-sign updates are clinician-only territory.
 */
@RestController
@RequestMapping("/api/v1/clinical-signs")
@RequiredArgsConstructor
public class ClinicalSignController {

    private final ClinicalSignService service;

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<ClinicalSignEventResponse>>> getHistory(
            @PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(service.getHistoryForVisit(visitId)));
    }

    @GetMapping("/visit/{visitId}/current")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<ClinicalSignEventResponse>>> getCurrentState(
            @PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(service.getCurrentStateForVisit(visitId)));
    }

    @GetMapping("/visit/{visitId}/sign/{signCode}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<ClinicalSignEventResponse>>> getSignHistory(
            @PathVariable UUID visitId, @PathVariable String signCode) {
        return ResponseEntity.ok(ApiResponse.success(service.getSignHistory(visitId, signCode)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<List<ClinicalSignEventResponse>>> recordBatch(
            @Valid @RequestBody RecordClinicalSignsBatchRequest request) {
        List<ClinicalSignEventResponse> created = service.recordBatch(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Clinical signs recorded", created));
    }
}
