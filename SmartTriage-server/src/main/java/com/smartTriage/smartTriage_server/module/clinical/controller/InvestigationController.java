package com.smartTriage.smartTriage_server.module.clinical.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.module.clinical.dto.InvestigationResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.OrderInvestigationRequest;
import com.smartTriage.smartTriage_server.module.clinical.dto.RecordInvestigationResultRequest;
import com.smartTriage.smartTriage_server.module.clinical.service.InvestigationService;
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
 * Investigation management endpoints.
 *
 *   POST   /api/v1/investigations                           → Order investigation
 *   PATCH  /api/v1/investigations/{id}/specimen-collected    → Mark specimen collected
 *   PATCH  /api/v1/investigations/{id}/in-progress           → Mark in progress
 *   PATCH  /api/v1/investigations/{id}/result                → Record result
 *   PATCH  /api/v1/investigations/{id}/cancel                → Cancel
 *   GET    /api/v1/investigations/{id}                       → Single record
 *   GET    /api/v1/investigations/visit/{visitId}            → Paginated list
 *   GET    /api/v1/investigations/visit/{visitId}/all        → Full list
 *   GET    /api/v1/investigations/visit/{visitId}/type/{type}→ By type
 *   GET    /api/v1/investigations/visit/{visitId}/pending    → Pending only
 */
@RestController
@RequestMapping("/api/v1/investigations")
@RequiredArgsConstructor
public class InvestigationController {

    private final InvestigationService investigationService;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<InvestigationResponse>> orderInvestigation(
            @Valid @RequestBody OrderInvestigationRequest request) {
        InvestigationResponse response = investigationService.orderInvestigation(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Investigation ordered", response));
    }

    @PatchMapping("/{id}/specimen-collected")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<InvestigationResponse>> markSpecimenCollected(
            @PathVariable UUID id) {
        InvestigationResponse response = investigationService.markSpecimenCollected(id);
        return ResponseEntity.ok(ApiResponse.success("Specimen collected", response));
    }

    @PatchMapping("/{id}/in-progress")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<InvestigationResponse>> markInProgress(
            @PathVariable UUID id) {
        InvestigationResponse response = investigationService.markInProgress(id);
        return ResponseEntity.ok(ApiResponse.success("Investigation in progress", response));
    }

    @PatchMapping("/{id}/result")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<InvestigationResponse>> recordResult(
            @PathVariable UUID id,
            @Valid @RequestBody RecordInvestigationResultRequest request) {
        request.setInvestigationId(id);
        InvestigationResponse response = investigationService.recordResult(request);
        return ResponseEntity.ok(ApiResponse.success("Investigation result recorded", response));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<InvestigationResponse>> cancelInvestigation(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        InvestigationResponse response = investigationService.cancelInvestigation(id, reason);
        return ResponseEntity.ok(ApiResponse.success("Investigation cancelled", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<InvestigationResponse>> getInvestigation(
            @PathVariable UUID id) {
        InvestigationResponse response = investigationService.getInvestigation(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    public ResponseEntity<ApiResponse<Page<InvestigationResponse>>> getInvestigationsByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<InvestigationResponse> response = investigationService
                .getInvestigationsByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/all")
    public ResponseEntity<ApiResponse<List<InvestigationResponse>>> getAllInvestigationsForVisit(
            @PathVariable UUID visitId) {
        List<InvestigationResponse> response = investigationService
                .getAllInvestigationsForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/type/{type}")
    public ResponseEntity<ApiResponse<List<InvestigationResponse>>> getInvestigationsByType(
            @PathVariable UUID visitId,
            @PathVariable InvestigationType type) {
        List<InvestigationResponse> response = investigationService
                .getInvestigationsByType(visitId, type);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/pending")
    public ResponseEntity<ApiResponse<List<InvestigationResponse>>> getPendingInvestigations(
            @PathVariable UUID visitId) {
        List<InvestigationResponse> response = investigationService
                .getPendingInvestigations(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
