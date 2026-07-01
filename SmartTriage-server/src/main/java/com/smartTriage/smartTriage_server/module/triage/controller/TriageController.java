package com.smartTriage.smartTriage_server.module.triage.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.triage.dto.PerformTriageRequest;
import com.smartTriage.smartTriage_server.module.triage.dto.TriageRecordResponse;
import com.smartTriage.smartTriage_server.module.triage.service.TriageService;
import com.smartTriage.smartTriage_server.module.visit.dto.VisitResponse;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
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
 * Triage endpoints — the clinical decision interface.
 * Triage operations are restricted to clinical staff.
 */
@RestController
@RequestMapping("/api/v1/triage")
@RequiredArgsConstructor
public class TriageController {

    private final TriageService triageService;
    private final VisitService visitService;

    /**
     * RBAC fix — Triage write authority is intentionally narrow.
     *
     * Permitted:
     * <ul>
     *   <li>Today's TRIAGE_NURSE (canonical authority)</li>
     *   <li>Anyone with charge-nurse authority — Designation.CHARGE_NURSE,
     *       current shift-lead badge, OR active CHARGE_NURSE shift function
     *       — so the floor lead can pick up triage when the Triage Nurse
     *       is overwhelmed.</li>
     * </ul>
     *
     * Denied (previously incorrectly permitted):
     * <ul>
     *   <li>SUPER_ADMIN, HOSPITAL_ADMIN — administrative roles, never clinical</li>
     *   <li>DOCTORs — they read triage records but do not author them</li>
     *   <li>ZONE_NURSE — assigned to a destination zone, not the triage station</li>
     * </ul>
     */
    @PostMapping
    @PreAuthorize("@clinicalAuthz.callerCanPerformTriage(authentication) "
            + "and @clinicalAuthz.canAccessVisit(authentication, #request.visitId)")
    public ResponseEntity<ApiResponse<TriageRecordResponse>> performTriage(
            @Valid @RequestBody PerformTriageRequest request) {
        TriageRecordResponse response = triageService.performTriage(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Triage completed", response));
    }

    @GetMapping("/visit/{visitId}/history")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<TriageRecordResponse>>> getTriageHistory(
            @PathVariable UUID visitId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<TriageRecordResponse> response = triageService.getTriageHistory(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/visit/{visitId}/latest")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<TriageRecordResponse>> getLatestTriage(
            @PathVariable UUID visitId) {
        TriageRecordResponse response = triageService.getLatestTriage(visitId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * The "placed but not yet formally triaged" worklist — acuity-split RED/ORANGE ambulance
     * arrivals (and Direct Resus admissions) that bypass the pre-triage desk queue yet still owe
     * a formal ED triage. Gated to the SAME authority that performs triage (triage nurse / charge
     * nurse / shift-lead) so the person who can act on it is exactly who sees it.
     */
    @GetMapping("/hospital/{hospitalId}/awaiting-ed-triage")
    @PreAuthorize("@clinicalAuthz.callerCanPerformTriage(authentication) "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<VisitResponse>>> awaitingEdTriage(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                visitService.getPlacedAwaitingEdTriage(hospitalId, pageable)));
    }
}
