package com.smartTriage.smartTriage_server.module.clinical.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import com.smartTriage.smartTriage_server.module.clinical.dto.CreateDiagnosisRequest;
import com.smartTriage.smartTriage_server.module.clinical.dto.DiagnosisResponse;
import com.smartTriage.smartTriage_server.module.clinical.service.DiagnosisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Diagnosis management endpoints.
 *
 *   POST   /api/v1/diagnoses                     → Create diagnosis
 *   PUT    /api/v1/diagnoses/{id}                 → Update diagnosis
 *   DELETE /api/v1/diagnoses/{id}                 → Soft-delete diagnosis
 *   GET    /api/v1/diagnoses/{id}                 → Single record
 *   GET    /api/v1/diagnoses/visit/{visitId}      → Paginated list
 *   GET    /api/v1/diagnoses/visit/{visitId}/all  → Full list
 *   GET    /api/v1/diagnoses/visit/{visitId}/type/{type} → By type
 */
@RestController
@RequestMapping("/api/v1/diagnoses")
@RequiredArgsConstructor
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE')")
    public ResponseEntity<ApiResponse<DiagnosisResponse>> createDiagnosis(
            @Valid @RequestBody CreateDiagnosisRequest request) {
        DiagnosisResponse response = diagnosisService.createDiagnosis(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Diagnosis created", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE')")
    public ResponseEntity<ApiResponse<DiagnosisResponse>> updateDiagnosis(
            @PathVariable UUID id,
            @Valid @RequestBody CreateDiagnosisRequest request) {
        DiagnosisResponse response = diagnosisService.updateDiagnosis(id, request);
        return ResponseEntity.ok(ApiResponse.success("Diagnosis updated", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<Void>> deleteDiagnosis(@PathVariable UUID id) {
        diagnosisService.deleteDiagnosis(id);
        return ResponseEntity.ok(ApiResponse.success("Diagnosis deleted", null));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DiagnosisResponse>> getDiagnosis(@PathVariable UUID id) {
        DiagnosisResponse response = diagnosisService.getDiagnosis(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<ApiResponse<Page<DiagnosisResponse>>> getDiagnosesByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<DiagnosisResponse> response = diagnosisService.getDiagnosesByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/all")
    public ResponseEntity<ApiResponse<List<DiagnosisResponse>>> getAllDiagnosesForVisit(
            @PathVariable UUID visitId) {
        List<DiagnosisResponse> response = diagnosisService.getAllDiagnosesForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/type/{type}")
    public ResponseEntity<ApiResponse<List<DiagnosisResponse>>> getDiagnosesByType(
            @PathVariable UUID visitId,
            @PathVariable DiagnosisType type) {
        List<DiagnosisResponse> response = diagnosisService.getDiagnosesByType(visitId, type);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
