package com.smartTriage.smartTriage_server.module.icu.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.icu.dto.IcuCapacityResponse;
import com.smartTriage.smartTriage_server.module.icu.dto.IcuEscalationRequest;
import com.smartTriage.smartTriage_server.module.icu.dto.IcuEscalationResponse;
import com.smartTriage.smartTriage_server.module.icu.dto.IcuResponseRequest;
import com.smartTriage.smartTriage_server.module.icu.entity.IcuEscalation;
import com.smartTriage.smartTriage_server.module.icu.mapper.IcuEscalationMapper;
import com.smartTriage.smartTriage_server.module.icu.service.IcuEscalationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

/**
 * ICU Escalation REST controller.
 * Provides endpoints for managing the full ICU escalation lifecycle:
 * request, notify, respond, assign bed, transfer, and cancel.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/icu")
@RequiredArgsConstructor
public class IcuEscalationController {

    private final IcuEscalationService icuEscalationService;

    /**
     * Request a new ICU escalation for a visit.
     */
    @PostMapping("/request")
    public ResponseEntity<ApiResponse<IcuEscalationResponse>> requestEscalation(
            @Valid @RequestBody IcuEscalationRequest request) {
        IcuEscalation escalation = icuEscalationService.requestEscalation(request);
        return ResponseEntity.ok(ApiResponse.success("ICU escalation requested", IcuEscalationMapper.toResponse(escalation)));
    }

    /**
     * Manually trigger auto-evaluation for a specific visit.
     */
    @PostMapping("/auto-evaluate/{visitId}")
    public ResponseEntity<ApiResponse<IcuEscalationResponse>> autoEvaluate(@PathVariable UUID visitId) {
        Optional<IcuEscalation> escalation = icuEscalationService.autoEvaluate(visitId);
        if (escalation.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success("ICU escalation auto-created",
                    IcuEscalationMapper.toResponse(escalation.get())));
        }
        return ResponseEntity.ok(ApiResponse.success("No ICU escalation indicated", null));
    }

    /**
     * Notify the ICU team about an escalation.
     */
    @PutMapping("/{id}/notify-team")
    public ResponseEntity<ApiResponse<IcuEscalationResponse>> notifyIcuTeam(@PathVariable UUID id) {
        IcuEscalation escalation = icuEscalationService.notifyIcuTeam(id);
        return ResponseEntity.ok(ApiResponse.success("ICU team notified", IcuEscalationMapper.toResponse(escalation)));
    }

    /**
     * Record the ICU team's response (accept or decline).
     */
    @PutMapping("/{id}/response")
    public ResponseEntity<ApiResponse<IcuEscalationResponse>> recordResponse(
            @PathVariable UUID id,
            @RequestBody IcuResponseRequest request) {
        IcuEscalation escalation = icuEscalationService.recordResponse(id, request);
        String message = escalation.getIcuBedAvailable() != null && escalation.getIcuBedAvailable()
                ? "ICU escalation accepted"
                : "ICU escalation declined";
        return ResponseEntity.ok(ApiResponse.success(message, IcuEscalationMapper.toResponse(escalation)));
    }

    /**
     * Assign an ICU bed to the escalation.
     */
    @PutMapping("/{id}/assign-bed")
    public ResponseEntity<ApiResponse<IcuEscalationResponse>> assignBed(
            @PathVariable UUID id,
            @RequestParam String bedNumber) {
        IcuEscalation escalation = icuEscalationService.assignBed(id, bedNumber);
        return ResponseEntity.ok(ApiResponse.success("ICU bed assigned", IcuEscalationMapper.toResponse(escalation)));
    }

    /**
     * Mark the patient as transferred to ICU.
     */
    @PutMapping("/{id}/transfer")
    public ResponseEntity<ApiResponse<IcuEscalationResponse>> transferToIcu(@PathVariable UUID id) {
        IcuEscalation escalation = icuEscalationService.transferToIcu(id);
        return ResponseEntity.ok(ApiResponse.success("Patient transferred to ICU", IcuEscalationMapper.toResponse(escalation)));
    }

    /**
     * Cancel an ICU escalation with a reason.
     */
    @PutMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<IcuEscalationResponse>> cancelEscalation(
            @PathVariable UUID id,
            @RequestParam String reason) {
        IcuEscalation escalation = icuEscalationService.cancelEscalation(id, reason);
        return ResponseEntity.ok(ApiResponse.success("ICU escalation cancelled", IcuEscalationMapper.toResponse(escalation)));
    }

    /**
     * Get paginated active (non-terminal) escalations for a hospital.
     */
    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("@clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<IcuEscalationResponse>>> getActiveEscalations(
            @PathVariable UUID hospitalId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<IcuEscalationResponse> response = icuEscalationService
                .getActiveEscalations(hospitalId, pageable)
                .map(IcuEscalationMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get the active escalation for a specific visit.
     */
    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<IcuEscalationResponse>> getEscalationForVisit(@PathVariable UUID visitId) {
        Optional<IcuEscalation> escalation = icuEscalationService.getEscalationForVisit(visitId);
        if (escalation.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(IcuEscalationMapper.toResponse(escalation.get())));
        }
        return ResponseEntity.ok(ApiResponse.success("No active ICU escalation for this visit", null));
    }

    /**
     * Get ICU bed capacity information for a hospital.
     */
    @GetMapping("/hospital/{hospitalId}/capacity")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<IcuCapacityResponse>> getIcuCapacity(@PathVariable UUID hospitalId) {
        IcuCapacityResponse capacity = icuEscalationService.getIcuCapacity(hospitalId);
        return ResponseEntity.ok(ApiResponse.success(capacity));
    }
}
