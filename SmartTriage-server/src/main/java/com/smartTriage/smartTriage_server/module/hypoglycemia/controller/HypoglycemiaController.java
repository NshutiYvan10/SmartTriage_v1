package com.smartTriage.smartTriage_server.module.hypoglycemia.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.module.hypoglycemia.dto.*;
import com.smartTriage.smartTriage_server.module.hypoglycemia.mapper.HypoglycemiaEventMapper;
import com.smartTriage.smartTriage_server.module.hypoglycemia.service.HypoglycemiaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HypoglycemiaController — endpoints for hypoglycemia enforcement and event management.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/hypoglycemia")
@RequiredArgsConstructor
public class HypoglycemiaController {

    private final HypoglycemiaService hypoglycemiaService;

    @PostMapping("/check/{visitId}")
    // Authz sweep — clinical roles + visit scope.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<HypoglycemiaCheckResponse>> checkAndEnforce(
            @PathVariable UUID visitId) {
        HypoglycemiaCheckResponse response = hypoglycemiaService.checkAndEnforce(visitId);
        return ResponseEntity.ok(ApiResponse.success("Hypoglycemia check completed", response));
    }

    @PutMapping("/{eventId}/treatment")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessHypoglycemiaEvent(authentication, #eventId)")
    public ResponseEntity<ApiResponse<HypoglycemiaEventResponse>> recordTreatment(
            @PathVariable UUID eventId,
            @Valid @RequestBody RecordTreatmentRequest request) {
        HypoglycemiaEventResponse response = HypoglycemiaEventMapper.toResponse(
                hypoglycemiaService.recordTreatment(eventId, request));
        return ResponseEntity.ok(ApiResponse.success("Treatment recorded", response));
    }

    @PutMapping("/{eventId}/repeat-glucose")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessHypoglycemiaEvent(authentication, #eventId)")
    public ResponseEntity<ApiResponse<HypoglycemiaEventResponse>> recordRepeatGlucose(
            @PathVariable UUID eventId,
            @Valid @RequestBody RepeatGlucoseRequest request) {
        HypoglycemiaEventResponse response = HypoglycemiaEventMapper.toResponse(
                hypoglycemiaService.recordRepeatGlucose(eventId, request));
        return ResponseEntity.ok(ApiResponse.success("Repeat glucose recorded", response));
    }

    @PutMapping("/{eventId}/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'NURSE') "
            + "and @clinicalAuthz.canAccessHypoglycemiaEvent(authentication, #eventId)")
    public ResponseEntity<ApiResponse<HypoglycemiaEventResponse>> resolveEvent(
            @PathVariable UUID eventId) {
        HypoglycemiaEventResponse response = HypoglycemiaEventMapper.toResponse(
                hypoglycemiaService.resolveEvent(eventId));
        return ResponseEntity.ok(ApiResponse.success("Event resolved", response));
    }

    @GetMapping("/visit/{visitId}")
    @PreAuthorize("@clinicalAuthz.canAccessVisit(authentication, #visitId)")
    public ResponseEntity<ApiResponse<List<HypoglycemiaEventResponse>>> getEventsForVisit(
            @PathVariable UUID visitId) {
        List<HypoglycemiaEventResponse> responses = hypoglycemiaService.getEventsForVisit(visitId)
                .stream()
                .map(HypoglycemiaEventMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    /**
     * Active (unresolved) hypoglycemia events at a hospital. Optionally
     * filtered by ED zone — passing {@code ?zone=ACUTE} restricts the list
     * to that zone, which is how an on-shift clinician sees only their zone's
     * events. With no zone parameter the caller must have cross-zone read
     * authority (admin / CN / shift-lead), enforced by the controller gate.
     */
    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize("@clinicalAuthz.canAccessHospital(authentication, #hospitalId) and "
            + "((#zone != null and @clinicalAuthz.canReceiveZoneAlerts(authentication, #hospitalId, #zone)) "
            + "or (#zone == null and @clinicalAuthz.canSeeAllZonesAtHospital(authentication, #hospitalId)))")
    public ResponseEntity<ApiResponse<List<HypoglycemiaEventResponse>>> getActiveEvents(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) com.smartTriage.smartTriage_server.common.enums.EdZone zone) {
        List<HypoglycemiaEventResponse> responses = hypoglycemiaService.getActiveEvents(hospitalId, zone)
                .stream()
                .map(HypoglycemiaEventMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
}
