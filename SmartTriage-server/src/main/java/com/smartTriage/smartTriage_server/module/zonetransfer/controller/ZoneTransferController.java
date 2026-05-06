package com.smartTriage.smartTriage_server.module.zonetransfer.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.zonetransfer.dto.ZoneTransferResponse;
import com.smartTriage.smartTriage_server.module.zonetransfer.service.ZoneTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ZoneTransferController — endpoints for the inter-zone transfer
 * state machine. Phase 2 of the zone-routing workflow.
 */
@RestController
@RequestMapping("/api/v1/zone-transfers")
@RequiredArgsConstructor
public class ZoneTransferController {

    private final ZoneTransferService zoneTransferService;

    /**
     * Receiving doctor accepts the transfer — visit's zone +
     * primary clinician change atomically.
     */
    @PostMapping("/{transferId}/accept")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ZoneTransferResponse>> accept(
            @PathVariable UUID transferId,
            @RequestBody(required = false) Map<String, String> body) {
        String handover = body == null ? null : body.get("handoverNote");
        return ResponseEntity.ok(ApiResponse.success(
                "Transfer accepted", zoneTransferService.accept(transferId, handover)));
    }

    /**
     * Receiving zone declines (e.g. resus full). Patient stays in
     * original zone; declined_reason explains why.
     */
    @PostMapping("/{transferId}/decline")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ZoneTransferResponse>> decline(
            @PathVariable UUID transferId,
            @RequestBody Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ResponseEntity.ok(ApiResponse.success(
                "Transfer declined", zoneTransferService.decline(transferId, reason)));
    }

    /**
     * Convert a pending transfer to RESUS_IN_PLACE — patient stays
     * physically where they are; receiving doctor takes co-
     * responsibility and brings equipment + escalation.
     */
    @PostMapping("/{transferId}/resus-in-place")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ZoneTransferResponse>> resusInPlace(
            @PathVariable UUID transferId,
            @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return ResponseEntity.ok(ApiResponse.success(
                "Treating in place", zoneTransferService.markResusInPlace(transferId, note)));
    }

    /**
     * Cancel a pending transfer — typically used when a system auto-
     * bump is immediately undone (false-positive sign correction).
     */
    @PostMapping("/{transferId}/cancel")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE')")
    public ResponseEntity<ApiResponse<ZoneTransferResponse>> cancel(
            @PathVariable UUID transferId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ResponseEntity.ok(ApiResponse.success(
                "Transfer cancelled", zoneTransferService.cancel(transferId, reason)));
    }

    /** All pending transfers across the hospital — for charge nurse. */
    @GetMapping("/hospital/{hospitalId}/pending")
    public ResponseEntity<ApiResponse<List<ZoneTransferResponse>>> pendingForHospital(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponse.success(
                zoneTransferService.pendingForHospital(hospitalId)));
    }

    /** Pending transfers into a specific zone. */
    @GetMapping("/hospital/{hospitalId}/pending/zone/{zone}")
    public ResponseEntity<ApiResponse<List<ZoneTransferResponse>>> pendingIntoZone(
            @PathVariable UUID hospitalId,
            @PathVariable EdZone zone) {
        return ResponseEntity.ok(ApiResponse.success(
                zoneTransferService.pendingIntoZone(hospitalId, zone)));
    }

    /** Visit-scoped pending transfer lookup (one-shot). */
    @GetMapping("/visit/{visitId}/pending")
    public ResponseEntity<ApiResponse<ZoneTransferResponse>> pendingForVisit(
            @PathVariable UUID visitId) {
        return zoneTransferService.findPendingForVisit(visitId)
                .map(t -> ResponseEntity.ok(ApiResponse.success(t)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.success((ZoneTransferResponse) null)));
    }

    /** Visit-scoped history of all transfers (audit log). */
    @GetMapping("/visit/{visitId}/history")
    public ResponseEntity<ApiResponse<List<ZoneTransferResponse>>> historyForVisit(
            @PathVariable UUID visitId) {
        return ResponseEntity.ok(ApiResponse.success(
                zoneTransferService.historyForVisit(visitId)));
    }
}
