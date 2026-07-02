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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #request.visitId)")
    public ResponseEntity<ApiResponse<InvestigationResponse>> orderInvestigation(
            @Valid @RequestBody OrderInvestigationRequest request) {
        InvestigationResponse response = investigationService.orderInvestigation(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Investigation ordered", response));
    }

    @PatchMapping("/{id}/specimen-collected")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessInvestigation(authentication, #id)")
    public ResponseEntity<ApiResponse<InvestigationResponse>> markSpecimenCollected(
            @PathVariable UUID id) {
        InvestigationResponse response = investigationService.markSpecimenCollected(id);
        return ResponseEntity.ok(ApiResponse.success("Specimen collected", response));
    }

    // LAB_TECHNICIAN is included so the diagnostics technician can drive an
    // imaging/ECG study through its worklist (perform → report). It stays SAFE:
    // requireNotLabManaged() still blocks any lab-routed investigation that has an
    // active LabOrder (that lifecycle belongs to the lab workflow), and
    // canAccessInvestigation() keeps it hospital-scoped.
    @PatchMapping("/{id}/in-progress")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'LAB_TECHNICIAN') "
            + "and @clinicalAuthz.canAccessInvestigation(authentication, #id)")
    public ResponseEntity<ApiResponse<InvestigationResponse>> markInProgress(
            @PathVariable UUID id) {
        InvestigationResponse response = investigationService.markInProgress(id);
        return ResponseEntity.ok(ApiResponse.success("Investigation in progress", response));
    }

    @PatchMapping("/{id}/result")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'LAB_TECHNICIAN') "
            + "and @clinicalAuthz.canAccessInvestigation(authentication, #id)")
    public ResponseEntity<ApiResponse<InvestigationResponse>> recordResult(
            @PathVariable UUID id,
            @Valid @RequestBody RecordInvestigationResultRequest request) {
        request.setInvestigationId(id);
        InvestigationResponse response = investigationService.recordResult(request);
        return ResponseEntity.ok(ApiResponse.success("Investigation result recorded", response));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessInvestigation(authentication, #id)")
    public ResponseEntity<ApiResponse<InvestigationResponse>> cancelInvestigation(
            @PathVariable UUID id,
            @RequestParam(required = false) String reason) {
        InvestigationResponse response = investigationService.cancelInvestigation(id, reason);
        return ResponseEntity.ok(ApiResponse.success("Investigation cancelled", response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@clinicalAuthz.canAccessInvestigation(authentication, #id)")
    public ResponseEntity<ApiResponse<InvestigationResponse>> getInvestigation(
            @PathVariable UUID id) {
        InvestigationResponse response = investigationService.getInvestigation(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<InvestigationResponse>>> getInvestigationsByVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<InvestigationResponse> response = investigationService
                .getInvestigationsByVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/all")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<InvestigationResponse>>> getAllInvestigationsForVisit(
            @PathVariable UUID visitId) {
        List<InvestigationResponse> response = investigationService
                .getAllInvestigationsForVisit(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/type/{type}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<InvestigationResponse>>> getInvestigationsByType(
            @PathVariable UUID visitId,
            @PathVariable InvestigationType type) {
        List<InvestigationResponse> response = investigationService
                .getInvestigationsByType(visitId, type);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/pending")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<InvestigationResponse>>> getPendingInvestigations(
            @PathVariable UUID visitId) {
        List<InvestigationResponse> response = investigationService
                .getPendingInvestigations(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Workflow 2 refinement — every investigation the authenticated
     * doctor has ordered, across every visit, newest first.
     * Replaces the "Doctor Lab Orders" sidebar entry: doctors no
     * longer manage lab inboxes; they track their own orders here.
     *
     * <p>Filters by {@code ordered_by_id} FK with a case-insensitive
     * name fallback for legacy rows (see
     * {@code InvestigationRepository.findByOrderedByIdOrLegacyName}).
     */
    @GetMapping("/doctor/me")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<ApiResponse<List<InvestigationResponse>>> getMyInvestigations(
            org.springframework.security.core.Authentication authentication) {
        Object principal = authentication != null ? authentication.getPrincipal() : null;
        if (!(principal instanceof com.smartTriage.smartTriage_server.module.user.entity.User user)) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        String fullName =
                ((user.getFirstName() != null ? user.getFirstName().trim() : "")
                        + " "
                        + (user.getLastName() != null ? user.getLastName().trim() : ""))
                .trim();
        if (fullName.isEmpty()) fullName = user.getEmail();
        return ResponseEntity.ok(ApiResponse.success(
                investigationService.getInvestigationsForDoctor(user.getId(), fullName)));
    }

    /**
     * Imaging &amp; Diagnostics worklist — every active imaging/ECG order at the
     * hospital that still needs a technician (ORDERED / IN_PROGRESS), across all
     * patients. The technician surface for orders the lab pipeline does NOT own,
     * so an ordered X-ray/CT/US/ECG can't silently vanish.
     *
     * <p>Same audience + hospital gate as the lab inbox
     * ({@code GET /lab/hospital/{hospitalId}/inbox}); results are further
     * zone-scoped server-side (tech + oversight see all; a zone nurse sees only
     * their covered zones).
     */
    @GetMapping("/hospital/{hospitalId}/imaging-worklist")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN', 'NURSE', 'DOCTOR') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<InvestigationResponse>>> getImagingWorklist(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(
                investigationService.getImagingWorklist(hospitalId)));
    }
}
