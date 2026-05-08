package com.smartTriage.smartTriage_server.module.lab.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.lab.dto.*;
import com.smartTriage.smartTriage_server.module.lab.service.LabOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Lab Order Controller — manages the full lab order lifecycle.
 *
 * RBAC summary (Phase 1):
 *  - DOCTOR / NURSE          : create order
 *  - DOCTOR                  : cancel order, acknowledge critical values
 *  - LAB_TECHNICIAN          : receive specimen, reject specimen,
 *                              start processing, record result
 *  - DOCTOR / NURSE          : collect specimen at bedside
 *  - SUPER_ADMIN             : everything
 *
 * Routes:
 *  POST   /api/v1/lab/order
 *  PUT    /api/v1/lab/{orderId}/collect-specimen
 *  PUT    /api/v1/lab/{orderId}/receive
 *  POST   /api/v1/lab/{orderId}/reject
 *  POST   /api/v1/lab/{orderId}/start-processing
 *  PUT    /api/v1/lab/{orderId}/result
 *  PUT    /api/v1/lab/{orderId}/acknowledge-critical
 *  PUT    /api/v1/lab/{orderId}/cancel
 *  GET    /api/v1/lab/visit/{visitId}
 *  GET    /api/v1/lab/hospital/{hospitalId}/inbox
 *  GET    /api/v1/lab/hospital/{hospitalId}/in-progress
 *  GET    /api/v1/lab/hospital/{hospitalId}/pending     (legacy alias)
 *  GET    /api/v1/lab/hospital/{hospitalId}/critical
 *  GET    /api/v1/lab/hospital/{hospitalId}/stat
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/lab")
@RequiredArgsConstructor
public class LabOrderController {

    private final LabOrderService labOrderService;

    // ====================================================================
    // CREATE ORDER (clinician-only)
    // ====================================================================

    @PostMapping("/order")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> orderLab(
            @Valid @RequestBody OrderLabRequest request) {
        LabOrderResponse response = labOrderService.orderLab(request.getVisitId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Lab order created", response));
    }

    // ====================================================================
    // WORKFLOW TRANSITIONS
    // ====================================================================

    @PutMapping("/{orderId}/collect-specimen")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> collectSpecimen(
            @PathVariable UUID orderId,
            @RequestParam(required = false) String collectedByName) {
        LabOrderResponse response = labOrderService.collectSpecimen(orderId, collectedByName);
        return ResponseEntity.ok(ApiResponse.success("Specimen collected", response));
    }

    @PutMapping("/{orderId}/receive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> receiveInLab(
            @PathVariable UUID orderId,
            @RequestBody(required = false) ReceiveSpecimenRequest request) {
        LabOrderResponse response = labOrderService.receiveInLab(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Order received by lab", response));
    }

    @PostMapping("/{orderId}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> rejectSpecimen(
            @PathVariable UUID orderId,
            @Valid @RequestBody RejectSpecimenRequest request) {
        LabOrderResponse response = labOrderService.rejectSpecimen(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Specimen rejected", response));
    }

    @PostMapping("/{orderId}/start-processing")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> startProcessing(
            @PathVariable UUID orderId,
            @RequestParam(required = false) String startedByName) {
        LabOrderResponse response = labOrderService.startProcessing(orderId, startedByName);
        return ResponseEntity.ok(ApiResponse.success("Processing started", response));
    }

    @PutMapping("/{orderId}/result")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> recordResult(
            @PathVariable UUID orderId,
            @Valid @RequestBody RecordLabResultRequest request) {
        LabOrderResponse response = labOrderService.recordResult(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Result recorded", response));
    }

    @PutMapping("/{orderId}/acknowledge-critical")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> acknowledgeCriticalValue(
            @PathVariable UUID orderId,
            @RequestBody(required = false) AcknowledgeCriticalRequest request) {
        LabOrderResponse response = labOrderService.acknowledgeCriticalValue(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Critical value acknowledged", response));
    }

    @PutMapping("/{orderId}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> cancelOrder(
            @PathVariable UUID orderId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String cancelledByName) {
        LabOrderResponse response = labOrderService.cancelOrder(orderId, reason, cancelledByName);
        return ResponseEntity.ok(ApiResponse.success("Order cancelled", response));
    }

    // ====================================================================
    // PHASE 2 — TWO-STEP VERIFICATION
    // ====================================================================

    /**
     * Senior tech verifies and releases an AWAITING_VERIFICATION
     * result. Locked to HEAD_LAB_TECHNICIAN designation; SUPER_ADMIN
     * can override for support cases.
     */
    @PostMapping("/{orderId}/verify")
    @PreAuthorize("hasRole('SUPER_ADMIN') or "
            + "(hasRole('LAB_TECHNICIAN') and @userAdminAuthz.hasDesignation(authentication, 'HEAD_LAB_TECHNICIAN'))")
    public ResponseEntity<ApiResponse<LabOrderResponse>> verifyResult(
            @PathVariable UUID orderId,
            @RequestBody(required = false) VerifyResultRequest request) {
        LabOrderResponse response = labOrderService.verifyResult(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Result verified and released", response));
    }

    /**
     * Senior tech rejects the result and bounces it back to the
     * junior. Status returns to PROCESSING; the junior re-enters.
     */
    @PostMapping("/{orderId}/verify-reject")
    @PreAuthorize("hasRole('SUPER_ADMIN') or "
            + "(hasRole('LAB_TECHNICIAN') and @userAdminAuthz.hasDesignation(authentication, 'HEAD_LAB_TECHNICIAN'))")
    public ResponseEntity<ApiResponse<LabOrderResponse>> rejectVerification(
            @PathVariable UUID orderId,
            @Valid @RequestBody RejectVerificationRequest request) {
        LabOrderResponse response = labOrderService.rejectVerification(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Result returned to junior tech", response));
    }

    /**
     * Junior tech emergency override — releases without senior
     * sign-off. Required reason is logged.
     */
    @PostMapping("/{orderId}/release-without-verification")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> releaseWithoutVerification(
            @PathVariable UUID orderId,
            @Valid @RequestBody OverrideVerificationRequest request) {
        LabOrderResponse response = labOrderService.overrideVerification(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Result released (verification bypassed)", response));
    }

    /** Senior-tech queue: results awaiting verification. */
    @GetMapping("/hospital/{hospitalId}/awaiting-verification")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<LabOrderResponse>>> getAwaitingVerification(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(labOrderService.getAwaitingVerification(hospitalId)));
    }

    // ====================================================================
    // QUERIES
    // ====================================================================

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'LAB_TECHNICIAN') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<Page<LabOrderResponse>>> getOrdersForVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<LabOrderResponse> response = labOrderService.getOrdersForVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /** Lab-tech inbox: orders waiting for lab action, STAT first. */
    @GetMapping("/hospital/{hospitalId}/inbox")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<LabOrderResponse>>> getInbox(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(labOrderService.getInboxForLab(hospitalId)));
    }

    /** Orders the lab is actively processing. */
    @GetMapping("/hospital/{hospitalId}/in-progress")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'LAB_TECHNICIAN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<LabOrderResponse>>> getInProgress(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(labOrderService.getInProgressForLab(hospitalId)));
    }

    @GetMapping("/hospital/{hospitalId}/pending")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'LAB_TECHNICIAN') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<LabOrderResponse>>> getPendingOrders(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<LabOrderResponse> response = labOrderService.getPendingOrders(hospitalId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/critical")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'LAB_TECHNICIAN') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<CriticalValueResponse>>> getCriticalResults(
            @PathVariable UUID hospitalId) {
        List<CriticalValueResponse> response = labOrderService.getCriticalResults(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/stat")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE', 'LAB_TECHNICIAN') "
            + "and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<LabOrderResponse>>> getStatOrders(
            @PathVariable UUID hospitalId) {
        List<LabOrderResponse> response = labOrderService.getStatOrders(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
