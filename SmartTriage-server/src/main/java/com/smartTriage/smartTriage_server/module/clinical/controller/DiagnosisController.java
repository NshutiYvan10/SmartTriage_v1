package com.smartTriage.smartTriage_server.module.clinical.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import com.smartTriage.smartTriage_server.module.clinical.dto.AmendDiagnosisRequest;
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

    /**
     * Nurse-scope RBAC fix — diagnosing is a DOCTOR act; nurses assess and
     * escalate but do not author diagnoses. Also hospital-scoped via the
     * request's visit (was previously role-gated only).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #request.visitId)")
    public ResponseEntity<ApiResponse<DiagnosisResponse>> createDiagnosis(
            @Valid @RequestBody CreateDiagnosisRequest request) {
        DiagnosisResponse response = diagnosisService.createDiagnosis(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Diagnosis created", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessDiagnosis(authentication, #id)")
    public ResponseEntity<ApiResponse<DiagnosisResponse>> updateDiagnosis(
            @PathVariable UUID id,
            @Valid @RequestBody CreateDiagnosisRequest request) {
        DiagnosisResponse response = diagnosisService.updateDiagnosis(id, request);
        return ResponseEntity.ok(ApiResponse.success("Diagnosis updated", response));
    }

    /**
     * Amend a diagnosis — a non-destructive edit. Creates a new linked version and
     * preserves the original; the change reason is mandatory. Doctor act.
     */
    @PostMapping("/{id}/amend")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessDiagnosis(authentication, #id)")
    public ResponseEntity<ApiResponse<DiagnosisResponse>> amendDiagnosis(
            @PathVariable UUID id,
            @Valid @RequestBody AmendDiagnosisRequest request) {
        DiagnosisResponse response = diagnosisService.amendDiagnosis(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Diagnosis amended — the original version is preserved", response));
    }

    /** Full version history for a diagnosis (root original + every amendment). */
    @GetMapping("/{id}/history")
    @PreAuthorize("@clinicalAuthz.canAccessDiagnosis(authentication, #id)")
    public ResponseEntity<ApiResponse<List<DiagnosisResponse>>> getDiagnosisHistory(@PathVariable UUID id) {
        List<DiagnosisResponse> response = diagnosisService.getHistory(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessDiagnosis(authentication, #id)")
    public ResponseEntity<ApiResponse<Void>> deleteDiagnosis(@PathVariable UUID id) {
        diagnosisService.deleteDiagnosis(id);
        return ResponseEntity.ok(ApiResponse.success("Diagnosis deleted", null));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@clinicalAuthz.canAccessDiagnosis(authentication, #id)")
    public ResponseEntity<ApiResponse<DiagnosisResponse>> getDiagnosis(@PathVariable UUID id) {
        DiagnosisResponse response = diagnosisService.getDiagnosis(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<DiagnosisResponse>>> getDiagnosesByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<DiagnosisResponse> response = diagnosisService.getDiagnosesByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/all")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<DiagnosisResponse>>> getAllDiagnosesForVisit(
            @PathVariable UUID visitId) {
        List<DiagnosisResponse> response = diagnosisService.getAllDiagnosesForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/type/{type}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<DiagnosisResponse>>> getDiagnosesByType(
            @PathVariable UUID visitId,
            @PathVariable DiagnosisType type) {
        List<DiagnosisResponse> response = diagnosisService.getDiagnosesByType(visitId, type);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
