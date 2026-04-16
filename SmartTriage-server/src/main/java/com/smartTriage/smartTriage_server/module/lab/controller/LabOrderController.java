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
 * POST   /api/v1/lab/order                              — Create lab order
 * PUT    /api/v1/lab/{orderId}/collect-specimen          — Mark specimen collected
 * PUT    /api/v1/lab/{orderId}/receive                   — Mark received in lab
 * PUT    /api/v1/lab/{orderId}/result                    — Record result
 * PUT    /api/v1/lab/{orderId}/acknowledge-critical      — Acknowledge critical value
 * PUT    /api/v1/lab/{orderId}/cancel                    — Cancel order
 * GET    /api/v1/lab/visit/{visitId}                     — Orders for visit
 * GET    /api/v1/lab/hospital/{hospitalId}/pending       — Pending orders
 * GET    /api/v1/lab/hospital/{hospitalId}/critical      — Unacknowledged critical results
 * GET    /api/v1/lab/hospital/{hospitalId}/stat          — Active STAT orders
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/lab")
@RequiredArgsConstructor
public class LabOrderController {

    private final LabOrderService labOrderService;

    // ====================================================================
    // CREATE ORDER
    // ====================================================================

    @PostMapping("/order")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE')")
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
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> collectSpecimen(
            @PathVariable UUID orderId,
            @RequestParam(required = false) String collectedByName) {
        LabOrderResponse response = labOrderService.collectSpecimen(orderId, collectedByName);
        return ResponseEntity.ok(ApiResponse.success("Specimen collected", response));
    }

    @PutMapping("/{orderId}/receive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> receiveInLab(
            @PathVariable UUID orderId) {
        LabOrderResponse response = labOrderService.receiveInLab(orderId);
        return ResponseEntity.ok(ApiResponse.success("Order received by lab", response));
    }

    @PutMapping("/{orderId}/result")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> recordResult(
            @PathVariable UUID orderId,
            @Valid @RequestBody RecordLabResultRequest request) {
        LabOrderResponse response = labOrderService.recordResult(orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Result recorded", response));
    }

    @PutMapping("/{orderId}/acknowledge-critical")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<LabOrderResponse>> acknowledgeCriticalValue(
            @PathVariable UUID orderId,
            @RequestParam String acknowledgedBy) {
        LabOrderResponse response = labOrderService.acknowledgeCriticalValue(orderId, acknowledgedBy);
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
    // QUERIES
    // ====================================================================

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<Page<LabOrderResponse>>> getOrdersForVisit(
            @PathVariable UUID visitId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<LabOrderResponse> response = labOrderService.getOrdersForVisit(visitId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/pending")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<Page<LabOrderResponse>>> getPendingOrders(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<LabOrderResponse> response = labOrderService.getPendingOrders(hospitalId, pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/critical")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<List<CriticalValueResponse>>> getCriticalResults(
            @PathVariable UUID hospitalId) {
        List<CriticalValueResponse> response = labOrderService.getCriticalResults(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/hospital/{hospitalId}/stat")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'TRIAGE_NURSE', 'NURSE')")
    public ResponseEntity<ApiResponse<List<LabOrderResponse>>> getStatOrders(
            @PathVariable UUID hospitalId) {
        List<LabOrderResponse> response = labOrderService.getStatOrders(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
